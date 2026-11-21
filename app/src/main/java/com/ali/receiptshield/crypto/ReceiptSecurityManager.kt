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
    val nonce: String
) {
    fun serialize(): String = "$version|$issuerId|$timestamp|$fileHash|$perceptualHash|$nonce"

    companion object {
        fun deserialize(raw: String): ReceiptPayload? {
            return try {
                val parts = raw.split("|")
                if (parts.size == 6 && (parts[0] == "RS_V2" || parts[0] == "RS_V1")) {
                    val version = parts[0]
                    val issuerId = parts[1]
                    val timestamp = parts[2].toLong()
                    val fileHash = parts[3]
                    val pHash = if (version == "RS_V2") parts[4] else ""
                    val nonce = if (version == "RS_V2") parts[5] else parts[4]

                    ReceiptPayload(version, issuerId, timestamp, fileHash, pHash, nonce)
                } else null
            } catch (e: Exception) { null }
        }
    }
}

class ReceiptSecurityManager {

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

    fun computePerceptualHash(bitmap: Bitmap): String {
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
            
            return hashBits.toString().chunked(4).joinToString("") { 
                Integer.parseInt(it, 2).toString(16) 
            }
        } finally {
            // FIX: Prevent memory leaks by recycling the temporary bitmap
            resized.recycle()
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
            } catch (e: Exception) {
                return false
            }
        }
        return distance <= tolerance
    }

    fun createStamp(liveHash: String, pHash: String, issuerId: String): ReceiptPayload {
        // FIX: Sanitize issuerId to prevent delimiter crashes
        val safeIssuerId = issuerId.replace("|", "_").trim().ifEmpty { "UNKNOWN" }
        
        return ReceiptPayload(
            version = "RS_V2",
            issuerId = safeIssuerId,
            timestamp = System.currentTimeMillis(),
            fileHash = liveHash,
            perceptualHash = pHash,
            nonce = UUID.randomUUID().toString().substring(0, 8)
        )
    }

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
        } catch (e: Exception) { null }
    }

    fun decodeQRCode(bitmap: Bitmap): String? {
        return try {
            // IMPROVEMENT: Downscale massive images to save memory before scanning
            val safeBitmap = if (bitmap.width > 2000 || bitmap.height > 2000) {
                val ratio = 2000f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
            } else {
                bitmap
            }

            val pixels = IntArray(safeBitmap.width * safeBitmap.height)
            safeBitmap.getPixels(pixels, 0, safeBitmap.width, 0, 0, safeBitmap.width, safeBitmap.height)
            val source = RGBLuminanceSource(safeBitmap.width, safeBitmap.height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(binaryBitmap).text
            
            // Cleanup if we created a new downscaled bitmap
            if (safeBitmap !== bitmap) safeBitmap.recycle()
            
            result
        } catch (e: Exception) { 
            null 
        }
    }
}
