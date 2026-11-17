package com.ali.receiptshield.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.File
import java.security.MessageDigest

data class ReceiptPayload(
    val version: Int = 1,
    val issuerId: String,
    val documentHash: String,
    val timestamp: Long,
    val customMetadata: String? = null
) {
    fun serialize(): String {
        return "RS_V$version|$issuerId|$documentHash|$timestamp"
    }

    companion object {
        fun deserialize(data: String): ReceiptPayload? {
            return try {
                val parts = data.split("|")
                if (parts.size < 4) return null
                if (!parts[0].startsWith("RS_V")) return null
                val version = parts[0].removePrefix("RS_V").toInt()
                ReceiptPayload(
                    version = version,
                    issuerId = parts[1],
                    documentHash = parts[2],
                    timestamp = parts[3].toLong()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

class ReceiptSecurityManager(private val context: Context) {

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }

    // Efficient Offline Chunked SHA-256 Hashing (Low RAM Footprint)
    fun generateFileHash(file: File): String {
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

    // Create Stamp Payload
    fun createStamp(file: File, issuerId: String): ReceiptPayload {
        val fileHash = generateFileHash(file)
        return ReceiptPayload(
            version = CURRENT_SCHEMA_VERSION,
            issuerId = issuerId,
            documentHash = fileHash,
            timestamp = System.currentTimeMillis(),
            customMetadata = "local_verified"
        )
    }

    // Verify Receipt Offline
    fun verifyReceipt(file: File, scannedPayload: ReceiptPayload): Boolean {
        if (scannedPayload.version > CURRENT_SCHEMA_VERSION) return false
        val currentFileHash = generateFileHash(file)
        return currentFileHash == scannedPayload.documentHash
    }

    // Generate QR Code Bitmap Locally via ZXing
    fun generateQRCodeBitmap(content: String, width: Int = 512, height: Int = 512): Bitmap? {
        return try {
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height)
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
