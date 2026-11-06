package com.example.geotime_consistency

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Trusted-time check. Never trusts the device clock:
 *  - server time comes from the HTTP `Date` header of a trusted endpoint
 *  - latency is corrected by sampling elapsedRealtime around the request
 *  - a persisted "boot anchor" (wallTime - elapsedRealtime) detects the
 *    clock being changed mid-session
 */
object NetworkTimeChecker {

    private const val PREFS = "geotime_consistency"
    private const val KEY_BOOT_ANCHOR = "boot_anchor_ms"
    // A real reboot resets elapsedRealtime; treat a fresh boot as a new anchor.
    private const val FRESH_BOOT_MS = 5 * 60 * 1000L
    private const val RETRY_DELAY_MS = 500L

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
        timeoutMs: Int,
        toleranceMs: Long,
    ): Result = withContext(Dispatchers.IO) {
        val deviceTime = System.currentTimeMillis()

        if (!hasValidatedNetwork(context)) {
            return@withContext Result(false, null, deviceTime, null, false)
        }

        // Try the whole chain twice — a single dropped packet or slow CDN
        // edge shouldn't mark all sources dead.
        val before = SystemClock.elapsedRealtime()
        val serverMs = (0..1).firstNotNullOfOrNull { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(RETRY_DELAY_MS)
            endpoints.firstNotNullOfOrNull { fetchServerTime(it, timeoutMs) }
        }
        val after = SystemClock.elapsedRealtime()

        // Network is validated but every time source failed — report
        // networkAvailable=true with no server time so the caller can
        // distinguish this from an actual offline state.
        if (serverMs == null) {
            return@withContext Result(true, null, deviceTime, null, false)
        }

        // Server time corresponds to the midpoint of the request round-trip.
        val rtt = after - before
        val correctedServer = serverMs + rtt / 2
        val delta = deviceTime - correctedServer

        val anchorTampered = checkBootAnchor(context, deviceTime, toleranceMs)
        val clockTampered = kotlin.math.abs(delta) > toleranceMs || anchorTampered

        Result(true, correctedServer, deviceTime, delta, clockTampered)
    }

    private fun hasValidatedNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun fetchServerTime(endpoint: String, timeoutMs: Int): Long? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
            }
            conn.connect()
            val dateHeader = conn.getHeaderField("Date") ?: return null
            httpDateFormat().parse(dateHeader)?.time
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
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
