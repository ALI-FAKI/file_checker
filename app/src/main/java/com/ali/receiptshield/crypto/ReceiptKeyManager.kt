package com.ali.receiptshield.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
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
        ) as? PrivateKey

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

    fun sign(data: ByteArray): String {
        require(data.isNotEmpty()) {
            "Cannot sign empty data."
        }

        val keyPair = getOrCreateKeyPair()

        val signer = Signature.getInstance(
            SIGNATURE_ALGORITHM
        )

        signer.initSign(keyPair.private)
        signer.update(data)

        val signatureBytes = signer.sign()

        return Base64.getEncoder().encodeToString(signatureBytes)
    }

    fun sign(data: String): String {
        require(data.isNotEmpty()) {
            "Cannot sign empty data."
        }
        return sign(data.toByteArray(Charsets.UTF_8))
    }

    fun verify(
        data: ByteArray,
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

            val keyFactory = KeyFactory.getInstance("RSA")

            val publicKey = keyFactory.generatePublic(
                X509EncodedKeySpec(publicKeyBytes)
            )

            val signatureBytes = Base64.getDecoder().decode(
                signatureBase64
            )

            val verifier = Signature.getInstance(
                SIGNATURE_ALGORITHM
            )

            verifier.initVerify(publicKey)
            verifier.update(data)

            verifier.verify(signatureBytes)

        } catch (_: Exception) {
            false
        }
    }

    fun verify(
        data: String,
        signatureBase64: String,
        publicKeyBase64: String
    ): Boolean {
        if (data.isEmpty()) return false
        return verify(
            data = data.toByteArray(Charsets.UTF_8),
            signatureBase64 = signatureBase64,
            publicKeyBase64 = publicKeyBase64
        )
    }

    fun getPublicKeyBase64(): String {
        val publicKey = getOrCreateKeyPair().public

        return Base64.getEncoder().encodeToString(
            publicKey.encoded
        )
    }
}
