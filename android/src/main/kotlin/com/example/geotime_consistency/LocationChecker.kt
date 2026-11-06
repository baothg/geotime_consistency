package com.example.geotime_consistency

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Location checks: fresh high-accuracy fix, mock-provider detection,
 * and distance-to-target.
 */
object LocationChecker {

    private const val FIX_TIMEOUT_MS = 15_000L

    enum class Outcome { OK, NO_PERMISSION, LOCATION_OFF, NO_FIX }

    data class Result(
        val outcome: Outcome,
        val isMock: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val accuracyMeters: Float?,
        val distanceMeters: Float?,
        val errorMessage: String?,
    )

    suspend fun check(
        context: Context,
        targetLat: Double,
        targetLng: Double,
    ): Result {
        if (!hasLocationPermission(context)) {
            return Result(Outcome.NO_PERMISSION, false, null, null, null, null,
                "Location permission not granted")
        }
        if (!isLocationEnabled(context)) {
            return Result(Outcome.LOCATION_OFF, false, null, null, null, null,
                "Location services disabled")
        }

        // A high-accuracy fix can take a long time indoors/underground —
        // never let validate() hang forever.
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val location: Location = try {
            val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) {
                fused.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token,
                ).await()
            }
            if (fix == null) {
                cts.cancel()
                return Result(Outcome.NO_FIX, false, null, null, null, null, "Location fix timed out")
            }
            fix
        } catch (e: Exception) {
            return Result(Outcome.NO_FIX, false, null, null, null, null, e.message)
        }

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
            errorMessage = null,
        )
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
