
package com.ali.receiptshield.crypto

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec

data class TrustedIssuer(
    val issuerId: String,
    val publicKey: String,
    val fingerprint: String,
    val registeredAt: Long
)

class TrustedIssuerRegistry(context: Context) {

    companion object {
        private const val PREFS_NAME =
            "receiptshield_trusted_issuers"

        private const val KEY_ISSUERS = "trusted_issuers"
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /**
     * Registers a public key as trusted.
     *
     * Only register keys whose fingerprints have been
     * independently confirmed with the issuer.
     */
    @Synchronized
    fun registerIssuer(
        issuerId: String,
        publicKeyBase64: String
    ): Boolean {

        if (
            issuerId.isBlank() ||
            publicKeyBase64.isBlank()
        ) {
            return false
        }

        return try {
            val decodedKey = Base64.decode(
                publicKeyBase64,
                Base64.DEFAULT
            )

            // Validate that the supplied key is a valid RSA public key.
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(
                    X509EncodedKeySpec(decodedKey)
                )

            val normalizedKey = Base64.encodeToString(
                publicKey.encoded,
                Base64.NO_WRAP
            )

            val fingerprint = calculateFingerprint(
                publicKey.encoded
            )

            val issuers = loadIssuers()

            // Avoid duplicate registrations.
            val alreadyRegistered = issuers.any {
                it.issuerId == issuerId &&
                    it.publicKey == normalizedKey
            }

            if (alreadyRegistered) {
                return true
            }

            val newIssuer = TrustedIssuer(
                issuerId = issuerId.trim(),
                publicKey = normalizedKey,
                fingerprint = fingerprint,
                registeredAt = System.currentTimeMillis()
            )

            issuers.add(newIssuer)

            saveIssuers(issuers)

        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks whether the issuer ID and public key
     * are both present in the trusted registry.
     */
    @Synchronized
    fun isTrusted(payload: ReceiptPayload): Boolean {

        if (payload.version != "RS_V3") {
            return false
        }

        if (
            payload.issuerId.isBlank() ||
            payload.publicKey.isBlank()
        ) {
            return false
        }

        return try {
            val normalizedKey = Base64.encodeToString(
                Base64.decode(
                    payload.publicKey,
                    Base64.DEFAULT
                ),
                Base64.NO_WRAP
            )

            loadIssuers().any {
                it.issuerId == payload.issuerId &&
                    it.publicKey == normalizedKey
            }

        } catch (_: Exception) {
            false
        }
    }

    /**
     * Finds a registered issuer using its ID and public key.
     */
    @Synchronized
    fun findTrustedIssuer(
        issuerId: String,
        publicKeyBase64: String
    ): TrustedIssuer? {

        if (
            issuerId.isBlank() ||
            publicKeyBase64.isBlank()
        ) {
            return null
        }

        return try {
            val normalizedKey = Base64.encodeToString(
                Base64.decode(
                    publicKeyBase64,
                    Base64.DEFAULT
                ),
                Base64.NO_WRAP
            )

            loadIssuers().firstOrNull {
                it.issuerId == issuerId &&
                    it.publicKey == normalizedKey
            }

        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns all locally registered trusted issuers.
     */
    @Synchronized
    fun getAllTrustedIssuers(): List<TrustedIssuer> {
        return loadIssuers().toList()
    }

    /**
     * Removes a specific trusted issuer key.
     */
    @Synchronized
    fun removeIssuer(
        issuerId: String,
        fingerprint: String
    ): Boolean {

        val issuers = loadIssuers()

        val removed = issuers.removeAll {
            it.issuerId == issuerId &&
                it.fingerprint == fingerprint
        }

        if (!removed) {
            return false
        }

        return saveIssuers(issuers)
    }

    /**
     * Calculates a SHA-256 fingerprint of the public key.
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

    private fun loadIssuers(): MutableList<TrustedIssuer> {

        val result = mutableListOf<TrustedIssuer>()

        val stored = preferences.getString(
            KEY_ISSUERS,
            "[]"
        ) ?: "[]"

        try {
            val array = JSONArray(stored)

            for (index in 0 until array.length()) {

                val item = array.getJSONObject(index)

                result.add(
                    TrustedIssuer(
                        issuerId = item.getString("issuerId"),
                        publicKey = item.getString("publicKey"),
                        fingerprint = item.getString("fingerprint"),
                        registeredAt = item.getLong("registeredAt")
                    )
                )
            }

        } catch (_: Exception) {
            // Return successfully parsed entries if storage is damaged.
        }

        return result
    }

    private fun saveIssuers(
        issuers: List<TrustedIssuer>
    ): Boolean {

        return try {
            val array = JSONArray()

            issuers.forEach { issuer ->

                val item = JSONObject().apply {
                    put("issuerId", issuer.issuerId)
                    put("publicKey", issuer.publicKey)
                    put("fingerprint", issuer.fingerprint)
                    put("registeredAt", issuer.registeredAt)
                }

                array.put(item)
            }

            preferences.edit()
                .putString(KEY_ISSUERS, array.toString())
                .commit()

        } catch (_: Exception) {
            false
        }
    }
}
