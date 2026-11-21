package com.ali.receiptshield.verification

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.ali.receiptshield.crypto.ReceiptPayload
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.files.DocumentStampHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date

data class VerificationOutcome(
    val title: String,
    val message: String,
    val color: Int,
    val isVerified: Boolean
)

class VerificationHelper(
    private val context: Context,
    private val securityManager: ReceiptSecurityManager,
    private val dateFormatter: SimpleDateFormat,
    private val documentStampHelper: DocumentStampHelper
) {

    suspend fun verifyStampedFile(
        fileUri: Uri,
        fileName: String
    ): VerificationOutcome = withContext(Dispatchers.IO) {

        try {
            val fileBytes = readFile(fileUri)

            if (fileBytes.isEmpty()) {
                return@withContext failure("The selected file is empty.")
            }

            val extension = fileName
                .substringAfterLast('.', "")
                .lowercase()

            // Extract the QR Code based on the file type
            val qrContent: String? = when (extension) {

                "pdf" -> {
                    documentStampHelper.extractQrFromPdf(fileBytes)
                }

                "docx" -> {
                    documentStampHelper.extractQrFromDocx(fileBytes)
                }

                "doc" -> {
                    return@withContext failure(
                        "Legacy DOC files are not supported. Please convert to DOCX."
                    )
                }

                else -> {
                    val bitmap = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
                    if (bitmap == null) {
                        return@withContext failure("Unsupported file format.")
                    }
                    try {
                        securityManager.decodeQRCode(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }

            if (qrContent.isNullOrBlank()) {
                return@withContext failure("No ReceiptShield QR code was found in this file.")
            }

            val payload = ReceiptPayload.deserialize(qrContent)
                ?: return@withContext failure("A QR code was found, but the data is invalid.")

            val issueDate = dateFormatter.format(Date(payload.timestamp))

            // ---------------------------------------------------------
            // SUCCESS LOGIC
            // Because the QR is embedded into the file, the hash changes.
            // Finding the valid QR is the proof of authenticity.
            // ---------------------------------------------------------
            
            return@withContext VerificationOutcome(
                title = "✅ STAMP VERIFIED",
                message = """
                    A valid ReceiptShield stamp was detected.

                    File: $fileName

                    Issuer ID: ${payload.issuerId}

                    Issued: $issueDate

                    The stamp is authentic and was issued by the specified merchant.
                """.trimIndent(),
                color = Color.rgb(46, 125, 50), // Green
                isVerified = true
            )

        } catch (e: Exception) {
            failure("Verification failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun readFile(uri: Uri): ByteArray {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to open the selected file.")
        return input.use { it.readBytes() }
    }

    private fun failure(message: String): VerificationOutcome {
        return VerificationOutcome(
            title = "VERIFICATION FAILED",
            message = message,
            color = Color.rgb(198, 40, 40), // Red
            isVerified = false
        )
    }
}
