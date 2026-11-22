
package com.ali.receiptshield.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

class IssuerKeyExporter(
    private val keyManager: ReceiptKeyManager = ReceiptKeyManager()
) {

    /**
     * Creates a shareable issuer registration package.
     *
     * The package contains only public information.
     * The private signing key is never exported.
     */
    fun exportRegistrationPackage(
        issuerId: String
    ): String {

        require(issuerId.isNotBlank()) {
            "Issuer ID cannot be empty."
        }

        val cleanIssuerId = issuerId.trim()

        val publicKeyBase64 =
            keyManager.getPublicKeyBase64()

        val publicKeyBytes = Base64.decode(
            publicKeyBase64,
            Base64.DEFAULT
        )

        val fingerprint = calculateFingerprint(
            publicKeyBytes
        )

        return JSONObject().apply {
            put("format", "RECEIPTSHIELD_ISSUER_V1")
            put("issuerId", cleanIssuerId)
            put("publicKey", publicKeyBase64)
            put("fingerprint", fingerprint)
        }.toString(4)
    }

    /**
     * Returns the SHA-256 fingerprint of the
     * currently installed issuer public key.
     */
    fun getPublicKeyFingerprint(): String {

        val publicKeyBase64 =
            keyManager.getPublicKeyBase64()

        val publicKeyBytes = Base64.decode(
            publicKeyBase64,
            Base64.DEFAULT
        )

        return calculateFingerprint(publicKeyBytes)
    }

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
