package com.ali.receiptshield.crypto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class ReceiptSecurityManager(
    private val keyManager: ReceiptKeyManager = ReceiptKeyManager()
) {

    fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun hashInputStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)

        var bytesRead = input.read(buffer)

        while (bytesRead != -1) {
            digest.update(buffer, 0, bytesRead)
            bytesRead = input.read(buffer)
        }

        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }

    fun computeManifestSha256(manifest: IntegrityManifestV4): String {
        return sha256Bytes(manifest.toCanonicalBytes())
    }

    fun computePerceptualHash(bitmap: Bitmap): String {

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            9,
            8,
            true
        )

        val pixels = IntArray(9 * 8)

        try {
            resized.getPixels(
                pixels,
                0,
                9,
                0,
                0,
                9,
                8
            )

            val hashBits = StringBuilder()

            for (y in 0 until 8) {
                for (x in 0 until 8) {

                    val left = pixels[y * 9 + x]
                    val right = pixels[y * 9 + x + 1]

                    val leftGray = (
                        Color.red(left) +
                            Color.green(left) +
                            Color.blue(left)
                        ) / 3

                    val rightGray = (
                        Color.red(right) +
                            Color.green(right) +
                            Color.blue(right)
                        ) / 3

                    hashBits.append(
                        if (leftGray > rightGray) "1" else "0"
                    )
                }
            }

            return hashBits.toString()
                .chunked(4)
                .joinToString("") {
                    Integer.parseInt(it, 2).toString(16)
                }

        } finally {
            if (resized !== bitmap) {
                resized.recycle()
            }
        }
    }

    fun isVisuallySimilar(
        hash1: String,
        hash2: String,
        tolerance: Int = 5
    ): Boolean {

        if (hash1.isEmpty() || hash2.isEmpty()) {
            return false
        }

        if (hash1.length != hash2.length) {
            return false
        }

        var distance = 0

        for (i in hash1.indices) {
            try {
                val val1 = Integer.parseInt(
                    hash1[i].toString(),
                    16
                )

                val val2 = Integer.parseInt(
                    hash2[i].toString(),
                    16
                )

                distance += Integer.bitCount(val1 xor val2)

            } catch (_: Exception) {
                return false
            }
        }

        return distance <= tolerance
    }

    fun createStampV4(
        receiptId: String = UUID.randomUUID().toString(),
        documentFormat: DocumentFormatV4,
        originalDocSha256: String,
        manifestSha256: String,
        issuerId: String,
        qrPlacement: PlacementMetaV4? = null,
        signatureMeta: SignatureMetaV4? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        nonce: String = UUID.randomUUID().toString().replace("-", "").take(16)
    ): ReceiptPayloadV4 {

        val safeIssuerId = issuerId.trim().ifEmpty { "UNKNOWN" }
        val publicKey = keyManager.getPublicKeyBase64()

        val unsignedPayload = ReceiptPayloadV4(
            version = RECEIPT_VERSION_V4,
            receiptId = receiptId.trim().ifEmpty { UUID.randomUUID().toString() },
            documentFormat = documentFormat,
            originalDocSha256 = originalDocSha256.lowercase().trim(),
            manifestSha256 = manifestSha256.lowercase().trim(),
            issuerId = safeIssuerId,
            timestampMillis = timestampMillis,
            nonce = nonce.trim(),
            qrPlacement = qrPlacement,
            signatureMeta = signatureMeta,
            publicKeyBase64 = publicKey,
            digitalSignatureBase64 = ""
        )

        val canonicalBytes = unsignedPayload.toCanonicalSigningBytes()
        val signature = signCanonicalBytes(canonicalBytes)

        return unsignedPayload.copy(
            digitalSignatureBase64 = signature
        )
    }

    fun verifyPayloadSignatureV4(payload: ReceiptPayloadV4): Boolean {
        if (payload.version != RECEIPT_VERSION_V4) {
            return false
        }
        if (
            payload.digitalSignatureBase64.isBlank() ||
            payload.publicKeyBase64.isBlank() ||
            payload.receiptId.isBlank() ||
            payload.issuerId.isBlank() ||
            payload.originalDocSha256.isBlank() ||
            payload.manifestSha256.isBlank()
        ) {
            return false
        }

        val canonicalBytes = payload.toCanonicalSigningBytes()
        return verifyCanonicalBytes(
            canonicalBytes = canonicalBytes,
            signatureBase64 = payload.digitalSignatureBase64,
            publicKeyBase64 = payload.publicKeyBase64
        )
    }

    fun parseAndVerifyQrPayloadV4(rawQrText: String): ReceiptPayloadV4? {
        return try {
            val payload = ReceiptPayloadV4.fromQrJsonString(rawQrText)
            if (verifyPayloadSignatureV4(payload)) payload else null
        } catch (_: Exception) {
            null
        }
    }

    private fun signCanonicalBytes(canonicalBytes: ByteArray): String {
        val byteSignMethod = keyManager.javaClass.methods.firstOrNull {
            it.name == "sign" && it.parameterTypes.size == 1 && it.parameterTypes[0] == ByteArray::class.java
        }
        if (byteSignMethod != null) {
            return byteSignMethod.invoke(keyManager, canonicalBytes) as String
        }
        return keyManager.sign(String(canonicalBytes, Charsets.UTF_8))
    }

    private fun verifyCanonicalBytes(
        canonicalBytes: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String
    ): Boolean {
        val byteVerifyMethod = keyManager.javaClass.methods.firstOrNull {
            it.name == "verify" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == ByteArray::class.java
        }
        if (byteVerifyMethod != null) {
            return (byteVerifyMethod.invoke(
                keyManager,
                canonicalBytes,
                signatureBase64,
                publicKeyBase64
            ) as? Boolean) ?: false
        }
        return keyManager.verify(
            data = String(canonicalBytes, Charsets.UTF_8),
            signatureBase64 = signatureBase64,
            publicKeyBase64 = publicKeyBase64
        )
    }

    fun generateQRCodeBitmap(
        content: String,
        width: Int = 512,
        height: Int = 512
    ): Bitmap? {

        return try {
            val writer = QRCodeWriter()

            val hints = mapOf(
                EncodeHintType.MARGIN to 1
            )

            val bitMatrix = writer.encode(
                content,
                BarcodeFormat.QR_CODE,
                width,
                height,
                hints
            )

            val bmp = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.RGB_565
            )

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(
                        x,
                        y,
                        if (bitMatrix[x, y]) {
                            Color.BLACK
                        } else {
                            Color.WHITE
                        }
                    )
                }
            }

            bmp

        } catch (_: Exception) {
            null
        }
    }

    fun decodeQRCode(bitmap: Bitmap): String? {

        var safeBitmap: Bitmap? = null

        return try {

            safeBitmap = if (
                bitmap.width > 2000 ||
                bitmap.height > 2000
            ) {
                val ratio = 2000f / maxOf(
                    bitmap.width,
                    bitmap.height
                )

                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            val pixels = IntArray(
                safeBitmap.width * safeBitmap.height
            )

            safeBitmap.getPixels(
                pixels,
                0,
                safeBitmap.width,
                0,
                0,
                safeBitmap.width,
                safeBitmap.height
            )

            val source = RGBLuminanceSource(
                safeBitmap.width,
                safeBitmap.height,
                pixels
            )

            val binaryBitmap = BinaryBitmap(
                HybridBinarizer(source)
            )

            MultiFormatReader()
                .decode(binaryBitmap)
                .text

        } catch (_: Exception) {
            null

        } finally {
            if (safeBitmap != null && safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }
        }
    }
}
