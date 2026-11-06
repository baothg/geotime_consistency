package com.example.geotime_consistency

import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** GeotimeConsistencyPlugin */
class GeotimeConsistencyPlugin :
    FlutterPlugin,
    MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val validating = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "geotime_consistency")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (call.method != "validate") {
            result.notImplemented()
            return
        }
        // Re-entrancy guard: rapid retries must not stack validations.
        if (!validating.compareAndSet(false, true)) {
            result.success(
                mapOf(
                    "status" to "error",
                    "failureReasons" to emptyList<String>(),
                    "warnings" to emptyList<String>(),
                    "errorMessage" to "A validation is already in progress",
                )
            )
            return
        }
        scope.launch {
            try {
                result.success(runValidation(call))
            } catch (e: Exception) {
                result.success(
                    mapOf(
                        "status" to "error",
                        "failureReasons" to emptyList<String>(),
                        "warnings" to emptyList<String>(),
                        "errorMessage" to (e.message ?: e.toString()),
                    )
                )
            } finally {
                validating.set(false)
            }
        }
    }

    private suspend fun runValidation(call: MethodCall): Map<String, Any?> {
        val endpoints = call.argument<List<String>>("timeEndpoints")
            ?: listOf("https://clients3.google.com/generate_204")
        val sntpServers = call.argument<List<String>>("sntpServers")
            ?: listOf("pool.ntp.org")
        val targetLat = call.argument<Double>("targetLatitude")
            ?: return errorResult("targetLatitude is required")
        val targetLng = call.argument<Double>("targetLongitude")
            ?: return errorResult("targetLongitude is required")
        val radius = call.argument<Double>("radiusMeters")
            ?: return errorResult("radiusMeters is required")
        val toleranceMs = call.argument<Number>("timeToleranceMs")?.toLong() ?: 60_000L
        val maxAccuracy = call.argument<Number>("maxAccuracyMeters")?.toDouble() ?: 100.0
        val timeoutMs = call.argument<Number>("networkTimeoutMs")?.toInt() ?: 5_000

        // The three checks are independent — run them concurrently on the
        // caller's scope: worst case becomes max(12s time budget, 15s
        // fix) not the sum.
        val timeDeferred = kotlinx.coroutines.CoroutineScope(coroutineContext).async {
            NetworkTimeChecker.check(
                appContext, endpoints, sntpServers, timeoutMs, toleranceMs
            )
        }
        val integrityDeferred = kotlinx.coroutines.CoroutineScope(coroutineContext).async {
            // Local signals spawn `which su`/`getprop` processes —
            // keep off the UI thread.
            withContext(Dispatchers.IO) { IntegrityChecker.check(appContext) }
        }
        val locDeferred = kotlinx.coroutines.CoroutineScope(coroutineContext).async {
            LocationChecker.check(appContext, targetLat, targetLng)
        }

        // Network gate — fail fast without waiting for the location fix.
        val time = timeDeferred.await()
        if (!time.networkAvailable) {
            locDeferred.cancel()
            integrityDeferred.cancel()
            return mapOf(
                "status" to "noNetwork",
                "failureReasons" to emptyList<String>(),
                "warnings" to emptyList<String>(),
                "deviceTimeMs" to time.deviceTimeMs,
            )
        }
        val integrity = integrityDeferred.await()
        val loc = locDeferred.await()

        // 4. GNSS satellite clock — fully offline backstop when every
        //    network time source failed.
        var serverTimeMs = time.serverTimeMs
        var deltaMs = time.deltaMs
        var clockTampered = time.clockTampered
        if (serverTimeMs == null && loc.gnssTimeMs != null) {
            serverTimeMs = loc.gnssTimeMs
            val gnssDelta = System.currentTimeMillis() - loc.gnssTimeMs
            deltaMs = gnssDelta
            if (kotlin.math.abs(gnssDelta) > toleranceMs) clockTampered = true
            // GNSS is a verified source — certify the boot anchor with it.
            NetworkTimeChecker.recordTrustedTime(appContext, loc.gnssTimeMs)
        }

        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (integrity.rooted) failures += "rooted"
        if (integrity.knoxTripped) warnings += "knoxTripped"
        if (integrity.emulator) warnings += "emulator"
        if (!integrity.autoTimeEnabled) warnings += "autoTimeOff"
        if (integrity.fakeGpsAppInstalled) warnings += "mockAppInstalled"

        if (clockTampered) failures += "timeSpoofed"

        var status = "pass"
        var errorMessage: String? = null
        when (loc.outcome) {
            LocationChecker.Outcome.OK -> {
                if (loc.isMock) {
                    failures += "mockLocation"
                } else {
                    val accuracy = loc.accuracyMeters
                    when {
                        accuracy != null && accuracy > maxAccuracy ->
                            status = "inconclusive"
                        // Fail only when outside the radius beyond the fix's
                        // own uncertainty — a user at the radius edge with a
                        // wobbly fix is not a cheater.
                        (loc.distanceMeters ?: Float.MAX_VALUE) - (accuracy ?: 0f) > radius ->
                            failures += "outOfRadius"
                    }
                }
            }
            LocationChecker.Outcome.NO_PERMISSION,
            LocationChecker.Outcome.LOCATION_OFF,
            LocationChecker.Outcome.NO_FIX -> {
                status = "inconclusive"
                errorMessage = loc.errorMessage
            }
        }
        if (failures.isNotEmpty()) {
            status = "fail"
        } else if (status == "pass" && serverTimeMs == null) {
            // Network was validated but no trusted time source answered —
            // not the user's fault; don't block, let the client log/retry.
            status = "timeSourceUnreachable"
        }

        return mapOf(
            "status" to status,
            "failureReasons" to failures,
            "warnings" to warnings,
            "serverTimeMs" to serverTimeMs,
            "deviceTimeMs" to time.deviceTimeMs,
            "timeDeltaMs" to deltaMs,
            "latitude" to loc.latitude,
            "longitude" to loc.longitude,
            "accuracyMeters" to loc.accuracyMeters?.toDouble(),
            "distanceMeters" to loc.distanceMeters?.toDouble(),
            "errorMessage" to errorMessage,
        )
    }

    private fun errorResult(message: String) = mapOf(
        "status" to "error",
        "failureReasons" to emptyList<String>(),
        "warnings" to emptyList<String>(),
        "errorMessage" to message,
    )

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
    }
}
