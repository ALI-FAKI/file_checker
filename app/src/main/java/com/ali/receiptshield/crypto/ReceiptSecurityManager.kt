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
    val nonce: String
) {
    fun serialize(): String = "$version|$issuerId|$timestamp|$fileHash|$nonce"

    companion object {
        fun deserialize(raw: String): ReceiptPayload? {
            return try {
                val parts = raw.split("|")
                if (parts.size == 5 && parts[0] == "RS_V1") {
                    ReceiptPayload(parts[0], parts[1], parts[2].toLong(), parts[3], parts[4])
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

    fun createStamp(liveHash: String, issuerId: String): ReceiptPayload {
        return ReceiptPayload(
            version = "RS_V1",
            issuerId = issuerId,
            timestamp = System.currentTimeMillis(),
            fileHash = liveHash,
            nonce = UUID.randomUUID().toString().substring(0, 8)
        )
    }

    fun verifyHash(liveHash: String, payload: ReceiptPayload): Boolean {
        if (payload.version != "RS_V1") return false
        return liveHash.equals(payload.fileHash, ignoreCase = true)
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
