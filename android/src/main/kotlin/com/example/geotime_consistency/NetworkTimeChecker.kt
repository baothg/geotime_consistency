package com.example.geotime_consistency

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Trusted-time check. Never trusts the device clock. Sources, ordered
 * by trust rather than speed:
 *  1. HTTPS `Date` headers (trusted endpoints) — TLS cannot be forged
 *     on-path
 *  2. SNTP (UDP 123) — backstop for networks that block the endpoints;
 *     forgeable, so only used when HTTPS is dead. Responses are
 *     field-validated (mode/stratum/originate timestamp, no LI=3)
 *  3. GNSS satellite clock (handled by the caller, piggybacked on the
 *     location fix) — fully offline backstop
 *  4. a persisted TRUSTED boot anchor (trustedTime - elapsedRealtime,
 *     only ever written from a verified source) detects the clock being
 *     changed — including before the first successful validation
 */
object NetworkTimeChecker {

    private const val PREFS = "geotime_consistency"
    private const val KEY_TRUSTED_ANCHOR = "trusted_boot_anchor_ms"
    // A real reboot resets elapsedRealtime; treat a fresh boot as a new anchor.
    private const val FRESH_BOOT_MS = 5 * 60 * 1000L
    private const val RETRY_DELAY_MS = 500L
    private const val PER_REQUEST_TIMEOUT_MS = 3_000
    private const val TOTAL_BUDGET_MS = 12_000L
    private const val NTP_UNIX_EPOCH_DIFF = 2_208_988_800L

    data class Result(
        val networkAvailable: Boolean,
        val serverTimeMs: Long?,
        val deviceTimeMs: Long?,
        val deltaMs: Long?,
        val clockTampered: Boolean,
    )

    suspend fun check(
        context: Context,
        endpoints: List<String>,
        sntpServers: List<String>,
        timeoutMs: Int,
        toleranceMs: Long,
    ): Result = withContext(Dispatchers.IO) {
        val deviceTime = System.currentTimeMillis()

        if (!hasValidatedNetwork(context)) {
            return@withContext Result(false, null, deviceTime, null, false)
        }

        // Blocked sockets/connections ignore coroutine cancellation, so
        // every fetch registers a closer; on budget expiry we close them
        // all, which unblocks the stranded I/O.
        val closers = CopyOnWriteArrayList<() -> Unit>()
        val serverMs = withTimeoutOrNull(TOTAL_BUDGET_MS) {
            fetchTrustedTime(endpoints, sntpServers, timeoutMs, closers)
        }
        closers.forEach { runCatching { it() } }

        // The trusted anchor is the local backstop — it must run even
        // when every remote source is dead.
        val anchorTampered = checkTrustedAnchor(context, deviceTime, toleranceMs)

        // Network is validated but every time source failed — report
        // networkAvailable=true with no server time so the caller can
        // distinguish this from an actual offline state (and try GNSS).
        if (serverMs == null) {
            return@withContext Result(true, null, deviceTime, null, anchorTampered)
        }

        recordTrustedTime(context, serverMs)

        val now = System.currentTimeMillis()
        val delta = now - serverMs
        val clockTampered = kotlin.math.abs(delta) > toleranceMs || anchorTampered

        Result(true, serverMs, now, delta, clockTampered)
    }

    /**
     * Stores the trusted boot anchor (trustedTime - elapsedRealtime).
     * Called after ANY verified source succeeds — HTTPS, SNTP, or GNSS
     * (GNSS callers use this from the plugin).
     */
    fun recordTrustedTime(context: Context, trustedMs: Long) {
        val anchor = trustedMs - SystemClock.elapsedRealtime()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_TRUSTED_ANCHOR, anchor).apply()
    }

    /**
     * Trusted sources by trust, not speed: HTTPS (TLS — an on-path
     * attacker cannot forge it) first; SNTP only when every HTTPS
     * endpoint is dead. Racing them would let a faster forged UDP reply
     * beat the trustworthy TLS answer.
     */
    private suspend fun fetchTrustedTime(
        endpoints: List<String>,
        sntpServers: List<String>,
        timeoutMs: Int,
        closers: MutableList<() -> Unit>,
    ): Long? {
        retryOnce { endpoints.firstNotNullOfOrNull { fetchHttpTime(it, timeoutMs, closers) } }
            ?.let { return it }
        return retryOnce { sntpServers.firstNotNullOfOrNull { fetchSntpTime(it, timeoutMs, closers) } }
    }

    /** Runs [block] twice with a short pause; returns its non-null result. */
    private suspend fun retryOnce(block: () -> Long?): Long? {
        block()?.let { return it }
        delay(RETRY_DELAY_MS)
        return block()
    }

    private fun hasValidatedNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Server-corrected current time via the `Date` header, RTT/2 compensated. */
    private fun fetchHttpTime(endpoint: String, timeoutMs: Int, closers: MutableList<() -> Unit>): Long? {
        var conn: HttpURLConnection? = null
        val closer: () -> Unit = { conn?.disconnect() }
        val perRequest = minOf(timeoutMs, PER_REQUEST_TIMEOUT_MS)
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = perRequest
                readTimeout = perRequest
                instanceFollowRedirects = false
            }
            closers += closer
            val before = SystemClock.elapsedRealtime()
            conn.connect()
            val dateHeader = conn.getHeaderField("Date") ?: return null
            val after = SystemClock.elapsedRealtime()
            val serverMs = httpDateFormat().parse(dateHeader)?.time ?: return null
            serverMs + (after - before) / 2
        } catch (_: Exception) {
            null
        } finally {
            closers -= closer
            conn?.disconnect()
        }
    }

    /**
     * Server-corrected current time via SNTP. Uses the 4-timestamp NTP
     * offset ((t2−t1)+(t3−t4))/2, so latency asymmetry is compensated by
     * the protocol itself rather than a midpoint guess.
     */
    private fun fetchSntpTime(server: String, timeoutMs: Int, closers: MutableList<() -> Unit>): Long? {
        val socket = DatagramSocket().apply { soTimeout = minOf(timeoutMs, PER_REQUEST_TIMEOUT_MS) }
        val closer: () -> Unit = { socket.close() }
        closers += closer
        return try {
            val t1 = System.currentTimeMillis()
            val request = ByteArray(48).apply {
                this[0] = 0x1B // LI=0, VN=4, Mode=3 (client)
            }
            writeTimestamp(request, 40, t1)

            // DatagramSocket is not Closeable on older Android APIs —
            // close it manually (and via `closers` on budget expiry).
            // NOTE: InetAddress.getByName can still block past the
            // deadline — DNS has no socket timeout; the overall budget
            // bounds the result, not the stranded thread.
            val addr = InetAddress.getByName(server)
            socket.send(DatagramPacket(request, request.size, addr, 123))
            val response = ByteArray(48)
            socket.receive(DatagramPacket(response, response.size))
            val t4 = System.currentTimeMillis()

            // Forgery guards: server-mode reply (mode 4), synchronised
            // server (LI != 3), sane stratum, echo of our originate
            // timestamp within a tight window. An on-path attacker can
            // still copy originate — the trusted anchor below is what
            // actually corroborates SNTP answers.
            val leap = (response[0].toInt() shr 6) and 0x3
            val mode = response[0].toInt() and 0x7
            val stratum = response[1].toInt() and 0xFF
            val originate = readTimestamp(response, 24)
            if (leap == 3 || mode != 4 || stratum !in 1..15 ||
                kotlin.math.abs(originate - t1) > 1_000
            ) return null

            val t2 = readTimestamp(response, 32) // server receive
            val t3 = readTimestamp(response, 40) // server transmit
            val offset = ((t2 - t1) + (t3 - t4)) / 2
            t4 + offset
        } catch (_: Exception) {
            null
        } finally {
            closers -= closer
            socket.close()
        }
    }

    private fun writeTimestamp(buf: ByteArray, offset: Int, unixMs: Long) {
        val secs = unixMs / 1000 + NTP_UNIX_EPOCH_DIFF
        val frac = (unixMs % 1000) * 0x100000000L / 1000
        for (i in 0..3) buf[offset + i] = (secs shr (8 * (3 - i))).toByte()
        for (i in 0..3) buf[offset + 4 + i] = (frac shr (8 * (3 - i))).toByte()
    }

    private fun readTimestamp(buf: ByteArray, offset: Int): Long {
        var secs = 0L
        var frac = 0L
        for (i in 0..3) secs = (secs shl 8) or (buf[offset + i].toLong() and 0xFF)
        for (i in 0..3) frac = (frac shl 8) or (buf[offset + 4 + i].toLong() and 0xFF)
        return (secs - NTP_UNIX_EPOCH_DIFF) * 1000 + frac * 1000 / 0x100000000L
    }

    private fun httpDateFormat() =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }

    /**
     * Trusted boot anchor = the wall-clock boot instant as certified by a
     * verified time source (recordTrustedTime). The CURRENT wall clock's
     * boot instant is nowMs - elapsedRealtime; if it disagrees with the
     * trusted anchor beyond tolerance, the clock was moved.
     *
     * Deliberately never written from an unverified clock — a spoofed
     * first run cannot poison the anchor. Skip detection while uptime is
     * low: a real reboot legitimately moves the wall anchor.
     *
     * shortcut: a reboot inside FRESH_BOOT_MS still creates a blind
     * window before the next trusted validation. Upgrade: server-side
     * anchoring.
     */
    private fun checkTrustedAnchor(context: Context, nowMs: Long, toleranceMs: Long): Boolean {
        if (SystemClock.elapsedRealtime() < FRESH_BOOT_MS) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trusted = prefs.getLong(KEY_TRUSTED_ANCHOR, Long.MIN_VALUE)
        if (trusted == Long.MIN_VALUE) return false

        val wallAnchor = nowMs - SystemClock.elapsedRealtime()
        return kotlin.math.abs(wallAnchor - trusted) > toleranceMs
    }
}
