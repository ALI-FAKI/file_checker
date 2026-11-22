
package com.ali.receiptshield.crypto

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec

data class IssuerRegistrationResult(
    val success: Boolean,
    val message: String,
    val fingerprint: String? = null
)

class IssuerRegistrationHelper(context: Context) {

    private val registry = TrustedIssuerRegistry(
        context.applicationContext
    )

    /**
     * Validates an issuer's public key and calculates
     * its SHA-256 fingerprint without registering it.
     */
    fun validatePublicKey(
        publicKeyBase64: String
    ): IssuerRegistrationResult {

        if (publicKeyBase64.isBlank()) {
            return IssuerRegistrationResult(
                success = false,
                message = "Public key cannot be empty."
            )
        }

        return try {
            val keyBytes = Base64.decode(
                publicKeyBase64,
                Base64.DEFAULT
            )

            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(
                    X509EncodedKeySpec(keyBytes)
                )

            val fingerprint = calculateFingerprint(
                publicKey.encoded
            )

            IssuerRegistrationResult(
                success = true,
                message = """
                    Public key is valid.

                    SHA-256 fingerprint:
                    $fingerprint

                    Confirm this fingerprint independently
                    with the issuer before registering.
                """.trimIndent(),
                fingerprint = fingerprint
            )

        } catch (_: Exception) {
            IssuerRegistrationResult(
                success = false,
                message = "Invalid RSA public key. Check the supplied key."
            )
        }
    }

    /**
     * Registers an issuer only after validating its public key.
     */
    fun registerIssuer(
        issuerId: String,
        publicKeyBase64: String,
        confirmedFingerprint: String
    ): IssuerRegistrationResult {

        val cleanIssuerId = issuerId.trim()

        if (cleanIssuerId.isBlank()) {
            return IssuerRegistrationResult(
                success = false,
                message = "Issuer ID cannot be empty."
            )
        }

        val validation = validatePublicKey(publicKeyBase64)

        if (!validation.success) {
            return validation
        }

        val actualFingerprint =
            validation.fingerprint ?: return IssuerRegistrationResult(
                success = false,
                message = "Unable to calculate public key fingerprint."
            )

        // Require an exact fingerprint confirmation.
        if (
            !actualFingerprint.equals(
                confirmedFingerprint.trim(),
                ignoreCase = true
            )
        ) {
            return IssuerRegistrationResult(
                success = false,
                message = """
                    Fingerprint confirmation failed.

                    Expected:
                    $actualFingerprint

                    Supplied:
                    ${confirmedFingerprint.trim()}

                    No issuer was registered.
                """.trimIndent(),
                fingerprint = actualFingerprint
            )
        }

        val registered = registry.registerIssuer(
            issuerId = cleanIssuerId,
            publicKeyBase64 = publicKeyBase64
        )

        return if (registered) {
            IssuerRegistrationResult(
                success = true,
                message = "Issuer successfully registered as trusted.",
                fingerprint = actualFingerprint
            )
        } else {
            IssuerRegistrationResult(
                success = false,
                message = "Issuer registration failed.",
                fingerprint = actualFingerprint
            )
        }
    }

    /**
     * Calculates a SHA-256 fingerprint of the RSA public key.
     */
    private fun calculateFingerprint(
        publicKeyBytes: ByteArray
    ): String {

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes)

        return digest.joinToString("") {
            "%02X".format(it)
        }
    }
}
