package com.example.geotime_consistency

import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
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
            }
        }
    }

    private suspend fun runValidation(call: MethodCall): Map<String, Any?> {
        val endpoints = call.argument<List<String>>("timeEndpoints")
            ?: listOf("https://clients3.google.com/generate_204")
        val targetLat = call.argument<Double>("targetLatitude")
            ?: return errorResult("targetLatitude is required")
        val targetLng = call.argument<Double>("targetLongitude")
            ?: return errorResult("targetLongitude is required")
        val radius = call.argument<Double>("radiusMeters")
            ?: return errorResult("radiusMeters is required")
        val toleranceMs = call.argument<Number>("timeToleranceMs")?.toLong() ?: 60_000L
        val maxAccuracy = call.argument<Number>("maxAccuracyMeters")?.toDouble() ?: 100.0
        val timeoutMs = call.argument<Number>("networkTimeoutMs")?.toInt() ?: 5_000

        // 1. Network gate — everything else needs it.
        val time = NetworkTimeChecker.check(appContext, endpoints, timeoutMs, toleranceMs)
        if (!time.networkAvailable) {
            return mapOf(
                "status" to "noNetwork",
                "failureReasons" to emptyList<String>(),
                "warnings" to emptyList<String>(),
                "deviceTimeMs" to time.deviceTimeMs,
            )
        }

        // 2. Device integrity (local signals — spawns `which su`/`getprop`
        //    processes; keep off the UI thread).
        val integrity = withContext(Dispatchers.IO) { IntegrityChecker.check(appContext) }

        // 3. Location fix + mock + radius.
        val loc = LocationChecker.check(appContext, targetLat, targetLng)

        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (integrity.rooted) failures += "rooted"
        if (integrity.knoxTripped) warnings += "knoxTripped"
        if (integrity.emulator) warnings += "emulator"
        if (!integrity.autoTimeEnabled) warnings += "autoTimeOff"
        if (integrity.fakeGpsAppInstalled) warnings += "mockAppInstalled"

        if (time.clockTampered) failures += "timeSpoofed"

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
        if (failures.isNotEmpty()) status = "fail"

        return mapOf(
            "status" to status,
            "failureReasons" to failures,
            "warnings" to warnings,
            "serverTimeMs" to time.serverTimeMs,
            "deviceTimeMs" to time.deviceTimeMs,
            "timeDeltaMs" to time.deltaMs,
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
