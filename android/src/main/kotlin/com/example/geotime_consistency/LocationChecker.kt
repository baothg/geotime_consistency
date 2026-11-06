package com.example.geotime_consistency

import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Location checks: fresh high-accuracy fix, mock-provider detection,
 * and distance-to-target. Also captures the GNSS satellite clock while
 * the fix is running — a fully offline trusted-time backstop.
 */
object LocationChecker {

    private const val FIX_TIMEOUT_MS = 15_000L
    private const val GPS_EPOCH_OFFSET_MS = 315_964_800_000L // 1980-01-06
    // Absolute plausibility window for GNSS-derived UTC.
    private const val GNSS_PLAUSIBLE_MIN = 1_577_836_800_000L // 2020-01-01
    private const val GNSS_PLAUSIBLE_MAX = 4_102_444_800_000L // 2100-01-01

    enum class Outcome { OK, NO_PERMISSION, LOCATION_OFF, NO_FIX }

    data class Result(
        val outcome: Outcome,
        val isMock: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val accuracyMeters: Float?,
        val distanceMeters: Float?,
        val gnssTimeMs: Long?,
        val errorMessage: String?,
    )

    suspend fun check(
        context: Context,
        targetLat: Double,
        targetLng: Double,
    ): Result {
        if (!hasLocationPermission(context)) {
            return fail(Outcome.NO_PERMISSION, "Location permission not granted")
        }
        if (!isLocationEnabled(context)) {
            return fail(Outcome.LOCATION_OFF, "Location services disabled")
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gnssTime = listenGnssClock(lm)

        // A high-accuracy fix can take a long time indoors/underground —
        // never let validate() hang forever.
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val location: Location = try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                fused.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token,
                ).await()
            } ?: run {
                cts.cancel()
                unregisterGnss(lm, gnssTime)
                return fail(Outcome.NO_FIX, "Location fix timed out",
                    gnssTime = gnssTime.result())
            }
        } catch (e: Exception) {
            unregisterGnss(lm, gnssTime)
            return fail(Outcome.NO_FIX, e.message, gnssTime = gnssTime.result())
        }
        unregisterGnss(lm, gnssTime)

        val target = Location("target").apply {
            latitude = targetLat
            longitude = targetLng
        }
        return Result(
            outcome = Outcome.OK,
            isMock = isMock(location),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            distanceMeters = location.distanceTo(target),
            gnssTimeMs = gnssTime.result(),
            errorMessage = null,
        )
    }

    private fun fail(outcome: Outcome, message: String?, gnssTime: Long? = null) =
        Result(outcome, false, null, null, null, null, gnssTime, message)

    /**
     * Subscribes to GNSS measurement events and completes [GnssTime.box]
     * with the first usable satellite clock (UTC estimate).
     *
     * utcTime = clock.timeNanos − fullBiasNanos − biasNanos − leapSecond
     * converted to wall-clock epoch. Requires FINE permission and real
     * satellite visibility; stays null indoors — that is expected.
     */
    private fun listenGnssClock(lm: LocationManager): GnssTime {
        val box = GnssTime()
        box.callback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                if (box.deferred.isCompleted) return
                val clock = event.clock
                if (!clock.hasFullBiasNanos() || !clock.hasBiasNanos()) return
                // GPS time since GPS epoch (1980-01-06); convert to Unix ms.
                // biasNanos is Double — round the whole conversion once.
                val gpsNs = clock.timeNanos - clock.fullBiasNanos - clock.biasNanos
                val leapS = if (clock.hasLeapSecond()) clock.leapSecond.toLong() else 0L
                val utcMs = (gpsNs / 1_000_000).toLong() + GPS_EPOCH_OFFSET_MS - leapS * 1000
                // Sanity: reject garbage clocks some OEMs emit. Bound must
                // be absolute — comparing against the device clock would
                // reject the real time exactly when it is spoofed.
                if (utcMs in GNSS_PLAUSIBLE_MIN..GNSS_PLAUSIBLE_MAX) {
                    box.deferred.complete(utcMs)
                }
            }
        }
        val callback = box.callback
        try {
            if (callback != null) lm.registerGnssMeasurementsCallback(callback)
        } catch (_: Exception) {
            // No FINE permission, unsupported chipset — GNSS stays null.
            box.callback = null
        }
        return box
    }

    private class GnssTime {
        var callback: GnssMeasurementsEvent.Callback? = null
        val deferred = CompletableDeferred<Long>()
        fun result(): Long? = try {
            deferred.getCompleted()
        } catch (_: Exception) {
            null
        }
    }

    private fun unregisterGnss(lm: LocationManager, box: GnssTime) {
        box.callback?.let {
            try {
                lm.unregisterGnssMeasurementsCallback(it)
            } catch (_: Exception) {
            }
        }
    }

    private fun isMock(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
