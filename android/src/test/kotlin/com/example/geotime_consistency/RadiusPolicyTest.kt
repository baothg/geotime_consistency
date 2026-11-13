package com.example.geotime_consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RadiusPolicyTest {

    // 1 deg longitude at lat 10 is ~109.6 km — 0.001 deg ≈ 109.6 m.
    private val target = RadiusPolicy.Coordinates(10.0, 106.0)

    @Test
    fun rawFixInsideRadiusPassesUnpulled() {
        val verdict = RadiusPolicy.validate(
            target.latitude, target.longitude,
            10.0, 106.0005, // ~55 m
            radiusMeters = 100.0,
        )!!

        assertEquals(0.0, verdict.pullPercent)
        assertEquals(10.0, verdict.latitude)
        assertEquals(106.0005, verdict.longitude)
    }

    @Test
    fun fixOutsideRadiusPassesAtFivePercentPull() {
        val raw = RadiusPolicy.Coordinates(10.0, 106.00095) // ~104 m

        val verdict = RadiusPolicy.validate(
            target.latitude, target.longitude,
            raw.latitude, raw.longitude,
            radiusMeters = 100.0,
        )!!

        // raw fails, a 5% pull lands at ~99 m.
        assertEquals(5.0, verdict.pullPercent)
        assertPulled(raw, verdict, keepFraction = 0.95)
    }

    @Test
    fun fixFarOutsideRadiusPassesAtFifteenPercentPull() {
        val raw = RadiusPolicy.Coordinates(10.0, 106.001) // ~110 m

        val verdict = RadiusPolicy.validate(
            target.latitude, target.longitude,
            raw.latitude, raw.longitude,
            radiusMeters = 100.0,
        )!!

        // raw and 5% pull fail, a 15% pull lands at ~93 m.
        assertEquals(15.0, verdict.pullPercent)
        assertPulled(raw, verdict, keepFraction = 0.85)
    }

    @Test
    fun fixBeyondAllPullStepsFails() {
        // ~220 m out: even a 15% pull leaves ~186 m > 100 m.
        assertNull(
            RadiusPolicy.validate(
                target.latitude, target.longitude,
                10.0, 106.002,
                radiusMeters = 100.0,
            )
        )
    }

    @Test
    fun pullKeepsLongitudeInsideDateLineBounds() {
        val t = RadiusPolicy.Coordinates(0.0, 179.999)
        val verdict = RadiusPolicy.validate(
            t.latitude, t.longitude,
            0.0, -179.999,
            radiusMeters = 500.0,
        )!!

        assertTrue(verdict.longitude in -180.0..180.0)
    }

    private fun assertPulled(
        raw: RadiusPolicy.Coordinates,
        verdict: RadiusPolicy.Verdict,
        keepFraction: Double,
    ) {
        val rawDistance = RadiusPolicy.distanceMeters(target, raw)
        val adjustedDistance = RadiusPolicy.distanceMeters(
            target,
            RadiusPolicy.Coordinates(verdict.latitude, verdict.longitude),
        )
        assertEquals(rawDistance * keepFraction, adjustedDistance, 0.5)
    }
}
