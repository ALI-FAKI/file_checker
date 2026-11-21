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

    suspend fun verifyFileWithStampSource(
        fileUri: Uri,
        stampSourceUri: Uri,
        fileName: String
    ): VerificationOutcome = withContext(Dispatchers.IO) {
        val rawBytes = context.contentResolver.openInputStream(stampSourceUri)?.use { it.readBytes() }
            ?: throw Exception("Cannot read selected stamp/QR file")

        val decodedPayloadString = extractPayloadFromBytes(rawBytes)
            ?: throw Exception("Could not find a valid ReceiptShield QR code or stamp payload in the selected file.")

        verifyFileAgainstPayloadString(fileUri, fileName, decodedPayloadString)
    }

    suspend fun verifyFileAgainstPayloadString(
        fileUri: Uri,
        fileName: String,
        payloadString: String
    ): VerificationOutcome = withContext(Dispatchers.IO) {
        val payload = ReceiptPayload.deserialize(payloadString.trim())
            ?: throw Exception("Invalid ReceiptShield payload format.")

        val liveHash = context.contentResolver.openInputStream(fileUri)?.use {
            securityManager.hashInputStream(it)
        } ?: throw Exception("Cannot open file for hashing")

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
                message = "AUTHENTIC (Re-encoded / Compressed)\n\n" +
                    "File: $fileName\n" +
                    "Status: Visual hash match\n" +
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

    private fun extractPayloadFromBytes(bytes: ByteArray): String? {
        // 1. Try decoding as QR image first
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap != null) {
            val qrText = securityManager.decodeQRCode(bitmap)
            if (!qrText.isNullOrBlank()) return qrText
        }

        // 2. Otherwise check if it's a text/stamp file containing RS_V1 / RS_V2 payload
        val asText = try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }

        if (asText.isNotBlank()) {
            val payloadLine = asText.lines().firstOrNull { it.trim().startsWith("Payload:") }
            if (payloadLine != null) {
                return payloadLine.substringAfter("Payload:").trim()
            }
            val directToken = asText.split(Regex("\\s+")).firstOrNull {
                it.startsWith("RS_V2|") || it.startsWith("RS_V1|")
            }
            if (directToken != null) return directToken.trim()
        }

        return null
    }

    fun parseColor(colorHex: String): Int = Color.parseColor(colorHex)
}
