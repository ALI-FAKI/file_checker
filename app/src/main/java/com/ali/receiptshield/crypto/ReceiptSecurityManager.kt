
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

data class ReceiptPayload(
    val version: String,
    val issuerId: String,
    val timestamp: Long,
    val fileHash: String,
    val perceptualHash: String,
    val nonce: String,
    val publicKey: String = "",
    val digitalSignature: String = ""
) {

    fun signingData(): String {
        return listOf(
            version,
            issuerId,
            timestamp.toString(),
            fileHash,
            perceptualHash,
            nonce,
            publicKey
        ).joinToString("|")
    }

    fun serialize(): String {
        return if (version == "RS_V3") {
            listOf(
                version,
                issuerId,
                timestamp.toString(),
                fileHash,
                perceptualHash,
                nonce,
                publicKey,
                digitalSignature
            ).joinToString("|")
        } else {
            listOf(
                version,
                issuerId,
                timestamp.toString(),
                fileHash,
                perceptualHash,
                nonce
            ).joinToString("|")
        }
    }

    companion object {

        fun deserialize(raw: String): ReceiptPayload? {
            return try {
                val parts = raw.split("|")

                when {
                    parts.size == 8 && parts[0] == "RS_V3" -> {

                        val timestamp = parts[2].toLong()

                        if (
                            parts[1].isBlank() ||
                            parts[3].isBlank() ||
                            parts[5].isBlank() ||
                            parts[6].isBlank() ||
                            parts[7].isBlank()
                        ) {
                            return null
                        }

                        ReceiptPayload(
                            version = parts[0],
                            issuerId = parts[1],
                            timestamp = timestamp,
                            fileHash = parts[3],
                            perceptualHash = parts[4],
                            nonce = parts[5],
                            publicKey = parts[6],
                            digitalSignature = parts[7]
                        )
                    }

                    parts.size == 6 &&
                        (parts[0] == "RS_V2" || parts[0] == "RS_V1") -> {

                        val version = parts[0]
                        val timestamp = parts[2].toLong()

                        ReceiptPayload(
                            version = version,
                            issuerId = parts[1],
                            timestamp = timestamp,
                            fileHash = parts[3],
                            perceptualHash = if (version == "RS_V2") {
                                parts[4]
                            } else {
                                ""
                            },
                            nonce = if (version == "RS_V2") {
                                parts[5]
                            } else {
                                parts[4]
                            }
                        )
                    }

                    else -> null
                }

            } catch (_: Exception) {
                null
            }
        }
    }
}

class ReceiptSecurityManager(
    private val keyManager: ReceiptKeyManager = ReceiptKeyManager()
) {

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

    fun createStamp(
        liveHash: String,
        pHash: String,
        issuerId: String
    ): ReceiptPayload {

        val safeIssuerId = issuerId
            .replace("|", "_")
            .trim()
            .ifEmpty { "UNKNOWN" }

        val publicKey = keyManager.getPublicKeyBase64()

        val unsignedPayload = ReceiptPayload(
            version = "RS_V3",
            issuerId = safeIssuerId,
            timestamp = System.currentTimeMillis(),
            fileHash = liveHash,
            perceptualHash = pHash,
            nonce = UUID.randomUUID().toString().substring(0, 8),
            publicKey = publicKey
        )

        val signature = keyManager.sign(
            unsignedPayload.signingData()
        )

        return unsignedPayload.copy(
            digitalSignature = signature
        )
    }

    fun verifyPayloadSignature(payload: ReceiptPayload): Boolean {

        if (payload.version != "RS_V3") {
            return false
        }

        return keyManager.verify(
            data = payload.signingData(),
            signatureBase64 = payload.digitalSignature,
            publicKeyBase64 = payload.publicKey
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
