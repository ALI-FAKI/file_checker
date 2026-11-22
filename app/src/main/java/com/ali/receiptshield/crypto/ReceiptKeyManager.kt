
package com.ali.receiptshield.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.Base64

class ReceiptKeyManager {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ReceiptShield_Signing_Key_V1"
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    }

    private fun getKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    }

    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        val keyStore = getKeyStore()

        val existingPrivateKey = keyStore.getKey(
            KEY_ALIAS,
            null
        ) as? java.security.PrivateKey

        val existingCertificate = keyStore.getCertificate(KEY_ALIAS)

        if (existingPrivateKey != null && existingCertificate != null) {
            return KeyPair(
                existingCertificate.publicKey,
                existingPrivateKey
            )
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE
        )

        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or
                KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
            )
            .setUserAuthenticationRequired(false)
            .build()

        generator.initialize(specification)

        return generator.generateKeyPair()
    }

    fun sign(data: String): String {
        require(data.isNotEmpty()) {
            "Cannot sign empty data."
        }

        val keyPair = getOrCreateKeyPair()

        val signer = Signature.getInstance(
            SIGNATURE_ALGORITHM
        )

        signer.initSign(keyPair.private)
        signer.update(data.toByteArray(Charsets.UTF_8))

        val signatureBytes = signer.sign()

        return Base64.getEncoder().encodeToString(signatureBytes)
    }

    fun verify(
        data: String,
        signatureBase64: String,
        publicKeyBase64: String
    ): Boolean {
        return try {
            if (
                data.isEmpty() ||
                signatureBase64.isBlank() ||
                publicKeyBase64.isBlank()
            ) {
                return false
            }

            val publicKeyBytes = Base64.getDecoder().decode(
                publicKeyBase64
            )

            val keyFactory = java.security.KeyFactory.getInstance("RSA")

            val publicKey = keyFactory.generatePublic(
                java.security.spec.X509EncodedKeySpec(publicKeyBytes)
            )

            val signatureBytes = Base64.getDecoder().decode(
                signatureBase64
            )

            val verifier = Signature.getInstance(
                SIGNATURE_ALGORITHM
            )

            verifier.initVerify(publicKey)
            verifier.update(data.toByteArray(Charsets.UTF_8))

            verifier.verify(signatureBytes)

        } catch (_: Exception) {
            false
        }
    }

    fun getPublicKeyBase64(): String {
        val publicKey = getOrCreateKeyPair().public

        return Base64.getEncoder().encodeToString(
            publicKey.encoded
        )
    }
}
