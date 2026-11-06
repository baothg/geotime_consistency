package com.example.geotime_consistency

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Local device-integrity signals: root indicators, auto-time flag,
 * emulator detection, Knox warranty state.
 *
 * For a stronger verdict, pair with Play Integrity API
 * (`MEETS_DEVICE_INTEGRITY`) verified on your backend — see README.
 */
object IntegrityChecker {

    private val suPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/su", "/system/bin/.ext/su", "/system/usr/we-need-root/su",
        "/system/app/Superuser.apk", "/data/local/xbin/su",
        "/data/local/bin/su", "/data/local/su",
    )

    private val rootPackages = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.kingroot.kinguser",
        "com.noshufou.android.su",
    )

    data class Result(
        val rooted: Boolean,
        val knoxTripped: Boolean,
        val emulator: Boolean,
        val autoTimeEnabled: Boolean,
        val fakeGpsAppInstalled: Boolean,
    )

    fun check(context: Context): Result {
        return Result(
            rooted = isRooted(context),
            knoxTripped = isKnoxTripped(),
            emulator = isEmulator(),
            autoTimeEnabled = isAutoTimeEnabled(context),
            fakeGpsAppInstalled = hasPackage(context, fakeGpsPackages),
        )
    }

    // Known fake-GPS package names; keep in sync with <queries> in the manifest.
    private val fakeGpsPackages = listOf(
        "com.lexa.fakegps",
        "com.theappninjas.fakegpsjoystick",
        "com.fakegps.mock",
        "com.incorporateapps.fakegps.fre",
        "com.usefullapps.fakegps",
        "com.hopefactory2021.fakegpslocation",
        "ru.gavrikov.mocklocations",
        "com.locationchanger",
    )

    private fun isRooted(context: Context): Boolean {
        if (suPaths.any { File(it).exists() }) return true
        if (Build.TAGS?.contains("test-keys") == true) return true
        if (hasPackage(context, rootPackages)) return true
        return try {
            Runtime.getRuntime().exec(arrayOf("which", "su"))
                .inputStream.bufferedReader().readLine() != null
        } catch (_: Exception) {
            false
        }
    }

    private fun hasPackage(context: Context, names: List<String>): Boolean {
        val pm = context.packageManager
        return names.any {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(it, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(it, 0)
                }
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Knox warranty bit. "0x0" = never tripped, "0x1" = bootloader was
     * unlocked at some point (may predate the current owner — warn only).
     * Android system properties are not Java properties — read via getprop.
     */
    private fun isKnoxTripped(): Boolean {
        return getProp("ro.boot.warranty_bit") == "1" ||
            getProp("ro.warranty_bit") == "1"
    }

    private fun getProp(name: String): String? {
        return try {
            Runtime.getRuntime().exec(arrayOf("getprop", name))
                .inputStream.bufferedReader().readLine()?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("vbox") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
            "google_sdk" == Build.PRODUCT
    }

    private fun isAutoTimeEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 1
        } catch (_: Exception) {
            true // assume on when unreadable
        }
    }
}
