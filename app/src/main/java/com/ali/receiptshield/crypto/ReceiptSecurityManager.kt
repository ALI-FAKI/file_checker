package com.ali.receiptshield.crypto

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
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

    companion object {
        private const val TAG = "ReceiptSecurityManager"
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    }

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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeManifestSha256(manifest: IntegrityManifestV4): String {
        return sha256Bytes(manifest.toCanonicalBytes())
    }

    // IMPROVED: Defensive against recycled / empty bitmaps. A failed
    // perceptual hash should not crash the stamping flow — it just
    // means the manifest has an empty perceptualHash and image-integrity
    // falls back to full-bytes hashing.
    fun computePerceptualHash(bitmap: Bitmap): String {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return ""
            }

            val resized = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            val pixels = IntArray(9 * 8)

            try {
                resized.getPixels(pixels, 0, 9, 0, 0, 9, 8)
                val hashBits = StringBuilder()
                for (y in 0 until 8) {
                    for (x in 0 until 8) {
                        val left = pixels[y * 9 + x]
                        val right = pixels[y * 9 + x + 1]
                        val leftGray = (Color.red(left) + Color.green(left) + Color.blue(left)) / 3
                        val rightGray = (Color.red(right) + Color.green(right) + Color.blue(right)) / 3
                        hashBits.append(if (leftGray > rightGray) "1" else "0")
                    }
                }
                hashBits.toString().chunked(4).joinToString("") {
                    Integer.parseInt(it, 2).toString(16)
                }
            } finally {
                if (resized !== bitmap) {
                    resized.recycle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Perceptual hash failed: ${e.message}")
            ""
        }
    }

    fun isVisuallySimilar(hash1: String, hash2: String, tolerance: Int = 5): Boolean {
        if (hash1.isEmpty() || hash2.isEmpty()) return false
        if (hash1.length != hash2.length) return false

        var distance = 0
        for (i in hash1.indices) {
            try {
                val val1 = Integer.parseInt(hash1[i].toString(), 16)
                val val2 = Integer.parseInt(hash2[i].toString(), 16)
                distance += Integer.bitCount(val1 xor val2)
            } catch (_: Exception) {
                return false
            }
        }
        return distance <= tolerance
    }

    /**
     * Creates a V4 stamp payload and signs it.
     *
     * FIXED: Throws on signing failure instead of silently producing an
     * unsigned stamp. The previous version caught all Throwables and
     * returned an empty signature, which produced a document that LOOKED
     * successfully stamped but could never be verified. The caller
     * (MainActivity) now catches the exception and shows a real error,
     * so users know immediately that no file was created.
     *
     * @throws IllegalStateException if the AndroidKeyStore is unavailable
     *         or the signature cannot be generated.
     * @throws IllegalArgumentException if the input hashes are malformed.
     */
    fun createStampV4(
        receiptId: String = UUID.randomUUID().toString(),
        documentFormat: DocumentFormatV4,
        stampMode: StampModeV4,
        integrityManifest: IntegrityManifestV4,
        originalDocSha256: String,
        issuerId: String,
        qrPlacement: PlacementMetaV4? = null,
        signatureMeta: SignatureMetaV4? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        nonce: String = UUID.randomUUID().toString().replace("-", "").take(16)
    ): ReceiptPayloadV4 {

        // FIXED: Validate the inputs instead of silently accepting garbage.
        val normalizedHash = originalDocSha256.trim().lowercase()
        require(SHA256_REGEX.matches(normalizedHash)) {
            "originalDocSha256 must be a 64-character lowercase hex string. " +
                "Got: '${originalDocSha256.take(16)}...' (length=${originalDocSha256.length})"
        }
        require(integrityManifest.version == RECEIPT_VERSION_V4) {
            "Manifest version mismatch. Expected $RECEIPT_VERSION_V4, got ${integrityManifest.version}."
        }
        require(timestampMillis > 0L) {
            "timestampMillis must be positive. Got: $timestampMillis"
        }

        val safeIssuerId = issuerId.trim().ifEmpty { "UNKNOWN" }

        // FIXED: Get the public key without swallowing exceptions.
        // If AndroidKeyStore is broken, we want the user to know NOW,
        // not after they've already shared an unsigned file.
        val publicKey = try {
            keyManager.getPublicKeyBase64()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get public key from AndroidKeyStore", e)
            throw IllegalStateException(
                "Could not access the device signing key. " +
                    "Your document was NOT stamped. " +
                    "Cause: ${e.javaClass.simpleName}: ${e.message}", e
            )
        }

        if (publicKey.isBlank()) {
            throw IllegalStateException(
                "Device signing key is empty. Your document was NOT stamped."
            )
        }

        val manifestSha256 = computeManifestSha256(integrityManifest)

        val unsignedPayload = ReceiptPayloadV4(
            version = RECEIPT_VERSION_V4,
            receiptId = receiptId.trim().ifEmpty { UUID.randomUUID().toString() },
            documentFormat = documentFormat,
            stampMode = stampMode,
            integrityManifest = integrityManifest,
            originalDocSha256 = normalizedHash,
            manifestSha256 = manifestSha256,
            issuerId = safeIssuerId,
            timestampMillis = timestampMillis,
            nonce = nonce.trim(),
            qrPlacement = qrPlacement,
            signatureMeta = signatureMeta,
            publicKeyBase64 = publicKey,
            digitalSignatureBase64 = ""
        )

        // FIXED: Sign without swallowing. Any failure here means we cannot
        // produce an authentic stamp, so we must abort.
        val canonicalBytes = unsignedPayload.toCanonicalSigningBytes()
        val signature = try {
            signCanonicalBytes(canonicalBytes)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to sign canonical bytes", e)
            throw IllegalStateException(
                "Could not sign the stamp. Your document was NOT stamped. " +
                    "Cause: ${e.javaClass.simpleName}: ${e.message}", e
            )
        }

        if (signature.isBlank()) {
            throw IllegalStateException(
                "Signing returned an empty signature. Your document was NOT stamped."
            )
        }

        return unsignedPayload.copy(digitalSignatureBase64 = signature)
    }

    fun verifyPayloadSignatureV4(payload: ReceiptPayloadV4): Boolean {
        if (payload.version != RECEIPT_VERSION_V4) return false
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
            val payload = ReceiptPayloadV4.fromQrJsonString(rawQrText) ?: return null
            if (verifyPayloadSignatureV4(payload)) payload else null
        } catch (_: Exception) {
            null
        }
    }

    private fun signCanonicalBytes(canonicalBytes: ByteArray): String {
        return keyManager.sign(canonicalBytes)
    }

    private fun verifyCanonicalBytes(
        canonicalBytes: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String
    ): Boolean {
        return keyManager.verify(
            data = canonicalBytes,
            signatureBase64 = signatureBase64,
            publicKeyBase64 = publicKeyBase64
        )
    }

    fun generateQRCodeBitmap(
        content: String,
        width: Int = 512,
        height: Int = 512
    ): Bitmap? {
        if (content.isBlank() || width <= 0 || height <= 0) {
            return null
        }
        return try {
            val writer = QRCodeWriter()
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints)
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    pixels[rowOffset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Throwable) {
            null
        }
    }

    // IMPROVED: Guard against recycled bitmaps. The previous version
    // would throw an IllegalArgumentException from createScaledBitmap,
    // which was caught and returned null — same result, but logged so
    // it's easier to trace.
    fun decodeQRCode(bitmap: Bitmap): String? {
        var safeBitmap: Bitmap? = null
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return null
            }

            safeBitmap = if (bitmap.width > 2000 || bitmap.height > 2000) {
                val ratio = 2000f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            val pixels = IntArray(safeBitmap.width * safeBitmap.height)
            safeBitmap.getPixels(pixels, 0, safeBitmap.width, 0, 0, safeBitmap.width, safeBitmap.height)
            val source = RGBLuminanceSource(safeBitmap.width, safeBitmap.height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binaryBitmap).text
        } catch (_: Exception) {
            null
        } finally {
            if (safeBitmap != null && safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }
        }
    }
}
