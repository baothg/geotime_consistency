package com.example.geotime_consistency

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

/**
 * Requests a Play Integrity token. The token is opaque here — it must be
 * sent to your backend, which decrypts it via Google and reads the
 * `deviceIntegrity` / `appIntegrity` / `accountDetails` verdicts.
 *
 * shortcut: the nonce is generated on-device, so it only binds the token
 * to this request, not to a server session. For replay protection have
 * your backend issue the nonce; pass it through if you add that.
 */
object PlayIntegrityChecker {

    private val random = SecureRandom()

    /** Returns the integrity token, or null if unavailable/not configured. */
    suspend fun token(context: Context, cloudProjectNumber: Long): String? {
        return try {
            val nonce = ByteArray(32).also(random::nextBytes)
                .joinToString("") { "%02x".format(it) }
            IntegrityManagerFactory.create(context)
                .requestIntegrityToken(
                    IntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber)
                        .setNonce(nonce)
                        .build()
                )
                .await()
                .token()
        } catch (_: Exception) {
            null // Play Services missing, quota exceeded, non-Play build...
        }
    }
}
