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

/**
 * Location checks: fresh high-accuracy fix, mock-provider detection,
 * and distance-to-target.
 */
object LocationChecker {

    enum class Outcome { OK, NO_PERMISSION, LOCATION_OFF, NO_FIX }

    data class Result(
        val outcome: Outcome,
        val isMock: Boolean,
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
            return Result(Outcome.NO_PERMISSION, false, null, null,
                "Location permission not granted")
        }
        if (!isLocationEnabled(context)) {
            return Result(Outcome.LOCATION_OFF, false, null, null,
                "Location services disabled")
        }

        val fused = LocationServices.getFusedLocationProviderClient(context)
        val location: Location = try {
            fused.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token,
            ).await()
        } catch (e: Exception) {
            return Result(Outcome.NO_FIX, false, null, null, e.message)
        } ?: return Result(Outcome.NO_FIX, false, null, null, "Null location fix")

        val target = Location("target").apply {
            latitude = targetLat
            longitude = targetLng
        }
        return Result(
            outcome = Outcome.OK,
            isMock = isMock(location),
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
