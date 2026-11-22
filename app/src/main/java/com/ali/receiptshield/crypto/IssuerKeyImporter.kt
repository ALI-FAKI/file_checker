package com.ali.receiptshield.crypto

import android.content.Context
import org.json.JSONObject

class IssuerKeyImporter(context: Context) {

    private val registrationHelper =
        IssuerRegistrationHelper(context.applicationContext)

    /**
     * Validates and imports a ReceiptShield issuer package for RS_V4 trust.
     *
     * The fingerprint must be independently confirmed
     * before the issuer is registered as trusted.
     */
    fun importRegistrationPackage(
        packageJson: String,
        confirmedFingerprint: String
    ): IssuerRegistrationResult {

        if (packageJson.isBlank()) {
            return IssuerRegistrationResult(
                success = false,
                message = "Registration package cannot be empty."
            )
        }

        return try {
            val packageObject = JSONObject(packageJson)

            val format = packageObject.optString("format")
            val issuerId = packageObject.optString("issuerId")
            val publicKey = packageObject.optString("publicKey")
            val suppliedFingerprint =
                packageObject.optString("fingerprint")

            if (format != "RECEIPTSHIELD_ISSUER_V1") {
                return IssuerRegistrationResult(
                    success = false,
                    message = "Unsupported issuer registration package."
                )
            }

            if (
                issuerId.isBlank() ||
                publicKey.isBlank() ||
                suppliedFingerprint.isBlank()
            ) {
                return IssuerRegistrationResult(
                    success = false,
                    message = "Registration package is missing required information."
                )
            }

            // Independently validate the supplied public key.
            val validation = registrationHelper.validatePublicKey(
                publicKey
            )

            if (!validation.success) {
                return validation
            }

            val actualFingerprint =
                validation.fingerprint ?: return IssuerRegistrationResult(
                    success = false,
                    message = "Unable to calculate public key fingerprint."
                )

            val cleanSuppliedFingerprint = normalizeFingerprint(suppliedFingerprint)
            val cleanConfirmedFingerprint = normalizeFingerprint(confirmedFingerprint)

            // Check that the fingerprint inside the package
            // matches the actual public key.
            if (
                !actualFingerprint.equals(
                    cleanSuppliedFingerprint,
                    ignoreCase = true
                )
            ) {
                return IssuerRegistrationResult(
                    success = false,
                    message = "Registration package fingerprint is invalid."
                )
            }

            // Require independent fingerprint confirmation
            // before registering the issuer.
            if (
                !actualFingerprint.equals(
                    cleanConfirmedFingerprint,
                    ignoreCase = true
                )
            ) {
                return IssuerRegistrationResult(
                    success = false,
                    message = """
                        Independent fingerprint confirmation failed.

                        Issuer ID: $issuerId

                        Actual fingerprint:
                        $actualFingerprint

                        No issuer was registered.
                    """.trimIndent(),
                    fingerprint = actualFingerprint
                )
            }

            registrationHelper.registerIssuer(
                issuerId = issuerId,
                publicKeyBase64 = publicKey,
                confirmedFingerprint = actualFingerprint
            )

        } catch (_: Exception) {
            IssuerRegistrationResult(
                success = false,
                message = "Unable to read issuer registration package. Invalid JSON."
            )
        }
    }

    private fun normalizeFingerprint(raw: String): String {
        return raw
            .replace(":", "")
            .replace(" ", "")
            .trim()
    }
}
