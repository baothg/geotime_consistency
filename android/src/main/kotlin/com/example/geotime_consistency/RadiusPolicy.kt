package com.example.geotime_consistency

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Staged radius check, same policy as location-multishot-capture: the
 * fix is pulled toward the target in steps ([PULL_STEPS]) and compared
 * against the unmodified radius each time. The first position inside
 * the radius wins, so the reported position is always the
 * least-adjusted one that validates.
 */
internal object RadiusPolicy {

    /** Percent of the distance pulled toward the target per attempt —
     *  kept in percent so [Verdict.pullPercent] stays an exact value
     *  clients can compare against. */
    private val PULL_STEPS_PERCENT = doubleArrayOf(0.0, 5.0, 15.0)

    data class Verdict(
        val latitude: Double,
        val longitude: Double,

        // Percent the fix was pulled toward the target (0 = raw).
        val pullPercent: Double,
    )

    data class Coordinates(
        val latitude: Double,
        val longitude: Double,
    )

    /** Null when the fix stays outside the radius at every pull step. */
    fun validate(
        targetLatitude: Double,
        targetLongitude: Double,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
    ): Verdict? {
        val target = Coordinates(targetLatitude, targetLongitude)
        val location = Coordinates(latitude, longitude)
        for (pullPercent in PULL_STEPS_PERCENT) {
            val adjusted = pullTowardTarget(target, location, pullPercent / 100.0)
            if (distanceMeters(target, adjusted) <= radiusMeters) {
                return Verdict(adjusted.latitude, adjusted.longitude, pullPercent)
            }
        }
        return null
    }

    /** Moves [location] [pull]·100% of the way toward [target]. */
    fun pullTowardTarget(
        target: Coordinates,
        location: Coordinates,
        pull: Double,
    ): Coordinates {
        if (pull == 0.0) {
            return location
        }
        val distance = distanceMeters(target, location)
        if (distance == 0.0) {
            return target
        }

        val targetLatitude = Math.toRadians(target.latitude)
        val targetLongitude = Math.toRadians(target.longitude)
        val locationLatitude = Math.toRadians(location.latitude)
        val locationLongitude = Math.toRadians(location.longitude)
        val longitudeDelta = locationLongitude - targetLongitude
        val bearing = atan2(
            sin(longitudeDelta) * cos(locationLatitude),
            cos(targetLatitude) * sin(locationLatitude) -
                sin(targetLatitude) * cos(locationLatitude) * cos(longitudeDelta),
        )
        val angularDistance = distance * (1.0 - pull) / EARTH_RADIUS_METERS
        val adjustedLatitude = asin(
            (sin(targetLatitude) * cos(angularDistance) +
                cos(targetLatitude) * sin(angularDistance) * cos(bearing))
                .coerceIn(-1.0, 1.0),
        )
        val adjustedLongitude = targetLongitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(targetLatitude),
            cos(angularDistance) - sin(targetLatitude) * sin(adjustedLatitude),
        )

        return Coordinates(
            latitude = Math.toDegrees(adjustedLatitude),
            longitude = normalizeLongitude(Math.toDegrees(adjustedLongitude)),
        )
    }

    fun distanceMeters(first: Coordinates, second: Coordinates): Double {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val haversine = sin(latitudeDelta / 2).let { it * it } +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private fun normalizeLongitude(longitude: Double): Double {
        return (longitude + 540.0) % 360.0 - 180.0
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
