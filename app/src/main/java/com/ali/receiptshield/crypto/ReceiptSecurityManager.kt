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
    val perceptualHash: String, // NEW: Visual fingerprint
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
                    // Backward compatibility for V1 (no pHash)
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

    // NEW: Generate a 64-bit Perceptual Hash (dHash) for images
    fun computePerceptualHash(bitmap: Bitmap): String {
        // Resize to 9x8 to calculate horizontal differences
        val resized = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val pixels = IntArray(9 * 8)
        resized.getPixels(pixels, 0, 9, 0, 0, 9, 8)

        val hashBits = StringBuilder()
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = pixels[y * 9 + x]
                val right = pixels[y * 9 + x + 1]
                // Compare grayscale values
                val leftGray = (Color.red(left) + Color.green(left) + Color.blue(left)) / 3
                val rightGray = (Color.red(right) + Color.green(right) + Color.blue(right)) / 3
                hashBits.append(if (leftGray > rightGray) "1" else "0")
            }
        }
        // Convert binary string to hex
        return hashBits.toString().chunked(4).joinToString("") { 
            Integer.parseInt(it, 2).toString(16) 
        }
    }

    // NEW: Compare two perceptual hashes using Hamming Distance
    fun isVisuallySimilar(hash1: String, hash2: String, tolerance: Int = 5): Boolean {
        if (hash1.isEmpty() || hash2.isEmpty()) return false
        if (hash1.length != hash2.length) return false
        
        var distance = 0
        for (i in hash1.indices) {
            val val1 = Integer.parseInt(hash1[i].toString(), 16)
            val val2 = Integer.parseInt(hash2[i].toString(), 16)
            // XOR the bits, count the differences
            distance += Integer.bitCount(val1 xor val2)
        }
        return distance <= tolerance
    }

    fun createStamp(liveHash: String, pHash: String, issuerId: String): ReceiptPayload {
        return ReceiptPayload(
            version = "RS_V2", // Upgraded version
            issuerId = issuerId,
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
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binaryBitmap).text
        } catch (e: Exception) { null }
    }
}
