package com.ali.receiptshield.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
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
     * Computes a SHA-256 hash of the file using chunked streaming for memory efficiency.
     */
    fun computeFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates a cryptographic receipt stamp payload.
     */
    fun createStamp(file: File, issuerId: String): ReceiptPayload {
        val fileHash = computeFileHash(file)
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
    fun verifyReceipt(file: File, payload: ReceiptPayload): Boolean {
        if (payload.version != "RS_V1") return false
        val currentFileHash = computeFileHash(file)
        return currentFileHash.equals(payload.fileHash, ignoreCase = true)
    }

    /**
     * Generates a ZXing QR code Bitmap from serialized payload string.
     */
    fun generateQRCodeBitmap(content: String, width: Int = 512, height: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height)
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
}
