package com.example.geotime_consistency

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Trusted-time check. Never trusts the device clock. Sources, in order
 * of preference by expected latency:
 *  1. race: HTTPS `Date` headers (trusted endpoints) vs SNTP (UDP 123)
 *     — different failure domains; first to answer wins
 *  2. GNSS satellite clock (handled by the caller, piggybacked on the
 *     location fix) — fully offline backstop
 *  3. a persisted "boot anchor" (wallTime - elapsedRealtime) detects the
 *     clock being changed mid-session even when no source answers
 */
object NetworkTimeChecker {

    private const val PREFS = "geotime_consistency"
    private const val KEY_BOOT_ANCHOR = "boot_anchor_ms"
    // A real reboot resets elapsedRealtime; treat a fresh boot as a new anchor.
    private const val FRESH_BOOT_MS = 5 * 60 * 1000L
    private const val RETRY_DELAY_MS = 500L
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

        val serverMs = raceToFirstTime(endpoints, sntpServers, timeoutMs)

        // Boot anchor is the local backstop — it must run even when every
        // remote source is dead.
        val anchorTampered = checkBootAnchor(context, deviceTime, toleranceMs)

        // Network is validated but every time source failed — report
        // networkAvailable=true with no server time so the caller can
        // distinguish this from an actual offline state (and try GNSS).
        if (serverMs == null) {
            return@withContext Result(true, null, deviceTime, null, anchorTampered)
        }

        val now = System.currentTimeMillis()
        val delta = now - serverMs
        val clockTampered = kotlin.math.abs(delta) > toleranceMs || anchorTampered

        Result(true, serverMs, now, delta, clockTampered)
    }

    /**
     * Races the HTTPS chain against the SNTP chain. The first source to
     * return a non-null time wins; each chain retries once. Returns null
     * only when every source fails.
     */
    private suspend fun raceToFirstTime(
        endpoints: List<String>,
        sntpServers: List<String>,
        timeoutMs: Int,
    ): Long? = coroutineScope {
        val winners = Channel<Long>(capacity = 2)
        val jobs = listOf(
            launch {
                retryOnce { endpoints.firstNotNullOfOrNull { fetchHttpTime(it, timeoutMs) } }
                    ?.let { winners.send(it) }
            },
            launch {
                retryOnce { sntpServers.firstNotNullOfOrNull { fetchSntpTime(it, timeoutMs) } }
                    ?.let { winners.send(it) }
            },
        )
        launch {
            jobs.joinAll()
            winners.close()
        }
        winners.receiveCatching().getOrNull()
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
    private fun fetchHttpTime(endpoint: String, timeoutMs: Int): Long? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
            }
            val before = SystemClock.elapsedRealtime()
            conn.connect()
            val dateHeader = conn.getHeaderField("Date") ?: return null
            val after = SystemClock.elapsedRealtime()
            val serverMs = httpDateFormat().parse(dateHeader)?.time ?: return null
            serverMs + (after - before) / 2
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Server-corrected current time via SNTP. Uses the 4-timestamp NTP
     * offset ((t2−t1)+(t3−t4))/2, so latency asymmetry is compensated by
     * the protocol itself rather than a midpoint guess.
     */
    private fun fetchSntpTime(server: String, timeoutMs: Int): Long? {
        return try {
            val t1 = System.currentTimeMillis()
            val request = ByteArray(48).apply {
                this[0] = 0x1B // LI=0, VN=4, Mode=3 (client)
            }
            writeTimestamp(request, 40, t1)

            // DatagramSocket is not Closeable on older Android APIs —
            // close it manually.
            val socket = DatagramSocket().apply { soTimeout = timeoutMs }
            try {
                val addr = InetAddress.getByName(server)
                socket.send(DatagramPacket(request, request.size, addr, 123))
                val response = ByteArray(48)
                socket.receive(DatagramPacket(response, response.size))
                val t4 = System.currentTimeMillis()

                val t2 = readTimestamp(response, 32) // server receive
                val t3 = readTimestamp(response, 40) // server transmit
                val offset = ((t2 - t1) + (t3 - t4)) / 2
                t4 + offset
            } finally {
                socket.close()
            }
        } catch (_: Exception) {
            null
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
     * Boot anchor = wallClock - elapsedRealtime, i.e. the wall-clock instant
     * the device booted. elapsedRealtime cannot be changed by the user, so
     * if the anchor moved while uptime is high, the clock was changed.
     *
     * shortcut: a reboot inside FRESH_BOOT_MS silently re-anchors, so a
     * clock change right after reboot is undetected. Upgrade: anchor
     * against server time server-side.
     */
    private fun checkBootAnchor(context: Context, nowMs: Long, toleranceMs: Long): Boolean {
        val anchor = nowMs - SystemClock.elapsedRealtime()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getLong(KEY_BOOT_ANCHOR, Long.MIN_VALUE)

        if (saved == Long.MIN_VALUE || SystemClock.elapsedRealtime() < FRESH_BOOT_MS) {
            prefs.edit().putLong(KEY_BOOT_ANCHOR, anchor).apply()
            return false
        }
        if (kotlin.math.abs(anchor - saved) <= toleranceMs) return false

        prefs.edit().putLong(KEY_BOOT_ANCHOR, anchor).apply()
        return true
    }
}
