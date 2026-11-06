package com.example.geotime_consistency

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val KEY_BOOT_COUNT = "boot_count"
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

        // Fetch in a separate async: blocked sockets/connections ignore
        // coroutine cancellation, so withTimeoutOrNull around the call
        // itself would still wait for them. Awaiting a separate deferred
        // lets the deadline return immediately.
        val closers = CopyOnWriteArrayList<() -> Unit>()
        val expired = AtomicBoolean(false)
        val fetch = CoroutineScope(coroutineContext).async(Dispatchers.IO) {
            fetchTrustedTime(endpoints, sntpServers, timeoutMs, closers, expired)
        }
        val source = withTimeoutOrNull(TOTAL_BUDGET_MS) { fetch.await() }
        if (source == null) {
            // Stop the chain from opening fresh requests, then kill any
            // in-flight socket — cancellation alone can't interrupt
            // blocking I/O.
            expired.set(true)
            fetch.cancel()
            closers.forEach { runCatching { it() } }
        }

        // The trusted anchor is the local backstop — it must run even
        // when every remote source is dead.
        val anchorTampered = checkTrustedAnchor(context, toleranceMs)

        // Network is validated but every time source failed — report
        // networkAvailable=true with no server time so the caller can
        // distinguish this from an actual offline state (and try GNSS).
        if (source == null) {
            return@withContext Result(true, null, deviceTime, null, anchorTampered)
        }

        val now = System.currentTimeMillis()
        val delta = now - source.ms
        val clockTampered = kotlin.math.abs(delta) > toleranceMs

        // A trusted source agreeing with the wall clock overrides a stale
        // anchor entirely — otherwise a wrong anchor (e.g. one stored
        // before BOOT_COUNT tracking existed, then a reboot) would lock
        // honest users into timeSpoofed forever. Only HTTPS may write the
        // anchor — SNTP is forgeable and must never re-seed it.
        if (!clockTampered && source.viaHttps) {
            recordTrustedTime(context, source.ms)
        }

        Result(true, source.ms, now, delta, clockTampered)
    }

    /**
     * Stores the trusted boot anchor (trustedTime - elapsedRealtime).
     * Called after ANY verified source succeeds — HTTPS, SNTP, or GNSS
     * (GNSS callers use this from the plugin).
     */
    fun recordTrustedTime(context: Context, trustedMs: Long) {
        val anchor = trustedMs - SystemClock.elapsedRealtime()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TRUSTED_ANCHOR, anchor)
            .putInt(KEY_BOOT_COUNT, bootCount(context))
            .apply()
    }

    private data class SourcedTime(val ms: Long, val viaHttps: Boolean)

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
        expired: AtomicBoolean,
    ): SourcedTime? {
        retryOnce(expired) {
            endpoints.firstNotNullOfOrNull {
                if (expired.get()) null else fetchHttpTime(it, timeoutMs, closers)
            }
        }?.let { return SourcedTime(it, true) }
        return retryOnce(expired) {
            sntpServers.firstNotNullOfOrNull {
                if (expired.get()) null else fetchSntpTime(it, timeoutMs, closers)
            }
        }?.let { SourcedTime(it, false) }
    }

    /** Runs [block] twice with a short pause; stops early once the
     *  overall budget expired so no fresh request is opened. */
    private suspend fun retryOnce(expired: AtomicBoolean, block: () -> Long?): Long? {
        if (expired.get()) return null
        block()?.let { return it }
        delay(RETRY_DELAY_MS)
        if (expired.get()) return null
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
        var socket: DatagramSocket? = null
        val closer: () -> Unit = { socket?.close() }
        return try {
            socket = DatagramSocket().apply { soTimeout = minOf(timeoutMs, PER_REQUEST_TIMEOUT_MS) }
            closers += closer
            val sock = socket!!
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
            sock.send(DatagramPacket(request, request.size, addr, 123))
            val response = ByteArray(48)
            sock.receive(DatagramPacket(response, response.size))
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
            socket?.close()
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
    /**
     * Android's own reboot counter. -1 when unavailable.
     */
    private fun bootCount(context: Context): Int {
        return try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.BOOT_COUNT,
                -1,
            )
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Trusted boot anchor = the wall-clock boot instant as certified by a
     * verified time source (recordTrustedTime). The CURRENT wall clock's
     * boot instant is nowMs - elapsedRealtime; if it disagrees with the
     * trusted anchor beyond tolerance, the clock was moved.
     *
     * Deliberately never written from an unverified clock — a spoofed
     * first run cannot poison the anchor. Detection is skipped while
     * uptime is low or when BOOT_COUNT shows a reboot happened since the
     * anchor was stored: a reboot legitimately moves the wall anchor and
     * must not flag honest users.
     *
     * shortcut: a reboot inside FRESH_BOOT_MS still creates a blind
     * window before the next trusted validation. Upgrade: server-side
     * anchoring.
     */
    private fun checkTrustedAnchor(context: Context, toleranceMs: Long): Boolean {
        if (SystemClock.elapsedRealtime() < FRESH_BOOT_MS) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trusted = prefs.getLong(KEY_TRUSTED_ANCHOR, Long.MIN_VALUE)
        if (trusted == Long.MIN_VALUE) return false

        // No stored boot count = anchor predates boot tracking or the
        // device can't report it — treat as invalid rather than risk a
        // stale-anchor lockout after a reboot.
        val savedBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)
        val currentBootCount = bootCount(context)
        if (savedBootCount == -1 ||
            (currentBootCount != -1 && savedBootCount != currentBootCount)
        ) {
            return false
        }

        // Wall anchor sampled here — the two clocks must be read together
        // or the check's own duration leaks into the anchor.
        val wallAnchor = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return kotlin.math.abs(wallAnchor - trusted) > toleranceMs
    }
}
