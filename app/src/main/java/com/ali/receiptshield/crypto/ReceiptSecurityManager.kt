package com.ali.receiptshield.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class ReceiptPayload(
    val version: String,
    val issuerId: String,
    val timestamp: Long,
    val fileHash: String,
    val nonce: String
) {
    fun serialize(): String {
        return "$version|$issuerId|$timestamp|$fileHash|$nonce"
    }

    companion object {
        fun deserialize(raw: String): ReceiptPayload? {
            return try {
                val parts = raw.split("|")
                if (parts.size == 5 && parts[0] == "RS_V1") {
                    ReceiptPayload(
                        version = parts[0],
                        issuerId = parts[1],
                        timestamp = parts[2].toLong(),
                        fileHash = parts[3],
                        nonce = parts[4]
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}

class ReceiptSecurityManager(private val context: Context) {

    /**
     * Computes a SHA-256 hash of a standard File.
     */
    fun computeFileHash(file: File): String {
        return file.inputStream().use { input ->
            computeHashFromStream(input)
        }
    }

    /**
     * Computes a SHA-256 hash from a Uri (Android File Picker / Scoped Storage safe).
     */
    fun computeFileHash(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            computeHashFromStream(input)
        } ?: throw Exception("Could not open file stream.")
    }

    /**
     * Core chunked streaming hashing logic (memory safe).
     */
    private fun computeHashFromStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead = input.read(buffer)
        while (bytesRead != -1) {
            digest.update(buffer, 0, bytesRead)
            bytesRead = input.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates a cryptographic receipt stamp payload.
     */
    fun createStamp(fileHash: String, issuerId: String): ReceiptPayload {
        return ReceiptPayload(
            version = "RS_V1",
            issuerId = issuerId,
            timestamp = System.currentTimeMillis(),
            fileHash = fileHash,
            nonce = UUID.randomUUID().toString().substring(0, 8)
        )
    }

    /**
     * Verifies if the file matches the embedded payload hash.
     */
    fun verifyReceipt(liveHash: String, payload: ReceiptPayload): Boolean {
        if (payload.version != "RS_V1") return false
        return liveHash.equals(payload.fileHash, ignoreCase = true)
    }

    /**
     * Generates a ZXing QR code Bitmap from serialized payload string.
     */
    fun generateQRCodeBitmap(content: String, width: Int = 512, height: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes a QR Code Bitmap back into a Payload String.
     */
    fun decodeQRCode(bitmap: Bitmap): String? {
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(binaryBitmap)
            result.text
        } catch (e: Exception) {
            null
        }
    }
}
