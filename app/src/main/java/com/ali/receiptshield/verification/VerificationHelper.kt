package com.ali.receiptshield.verification

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.ali.receiptshield.crypto.ReceiptPayload
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VerificationOutcome(
    val message: String,
    val colorHex: String
)

class VerificationHelper(
    private val context: Context,
    private val securityManager: ReceiptSecurityManager,
    private val dateFormatter: SimpleDateFormat
) {

    suspend fun verifyFileWithQr(
        fileUri: Uri,
        qrUri: Uri,
        fileName: String
    ): VerificationOutcome = withContext(Dispatchers.IO) {
        val qrBitmap = context.contentResolver.openInputStream(qrUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw Exception("Cannot read QR Image")

        val payloadString = withContext(Dispatchers.Default) {
            securityManager.decodeQRCode(qrBitmap)
        } ?: throw Exception("No valid QR code found in the selected image.")

        val payload = ReceiptPayload.deserialize(payloadString)
            ?: throw Exception("Invalid ReceiptShield payload format.")

        val liveHash = context.contentResolver.openInputStream(fileUri)?.use {
            securityManager.hashInputStream(it)
        } ?: throw Exception("Cannot open file")

        val livePHash = try {
            val bmp = context.contentResolver.openInputStream(fileUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
            if (bmp != null) securityManager.computePerceptualHash(bmp) else ""
        } catch (_: Exception) {
            ""
        }

        val formattedDate = dateFormatter.format(Date(payload.timestamp))

        when {
            liveHash.equals(payload.fileHash, ignoreCase = true) -> VerificationOutcome(
                message = "100% AUTHENTIC\n\n" +
                    "File: $fileName\n" +
                    "Status: Unmodified binary match\n" +
                    "Issuer: ${payload.issuerId}\n" +
                    "Stamped: $formattedDate",
                colorHex = "#0F9D58"
            )
            payload.perceptualHash.isNotEmpty() &&
                securityManager.isVisuallySimilar(livePHash, payload.perceptualHash) -> VerificationOutcome(
                message = "AUTHENTIC (Stamped Copy / Re-encoded)\n\n" +
                    "File: $fileName\n" +
                    "Status: Visual match with original image\n" +
                    "Issuer: ${payload.issuerId}\n" +
                    "Stamped: $formattedDate",
                colorHex = "#F59E0B"
            )
            else -> VerificationOutcome(
                message = "TAMPERED / FORGED\n\n" +
                    "File: $fileName\n" +
                    "Expected Hash: ${payload.fileHash.take(18)}...\n" +
                    "Actual Hash: ${liveHash.take(18)}...",
                colorHex = "#D93025"
            )
        }
    }

    fun parseColor(colorHex: String): Int = Color.parseColor(colorHex)
}
