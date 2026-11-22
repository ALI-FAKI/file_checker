
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

            // Extract the QR code according to the file type.
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
                    val bitmap = BitmapFactory.decodeByteArray(
                        fileBytes,
                        0,
                        fileBytes.size
                    )

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
                return@withContext failure(
                    "No ReceiptShield QR code was found in this file."
                )
            }

            val payload = ReceiptPayload.deserialize(qrContent)
                ?: return@withContext failure(
                    "A QR code was found, but its ReceiptShield data is invalid."
                )

            val issueDate = synchronized(dateFormatter) {
                dateFormatter.format(Date(payload.timestamp))
            }

            // A valid QR payload proves only that readable ReceiptShield
            // data was detected. It does not prove the document is unchanged,
            // that the issuer is genuine, or that the QR code was not copied.

            return@withContext VerificationOutcome(
                title = "STAMP DETECTED — NOT AUTHENTICATED",
                message = """
                    A valid ReceiptShield QR code was detected.

                    File: $fileName

                    Issuer ID in QR: ${payload.issuerId}

                    Recorded issue date: $issueDate

                    SECURITY NOTICE:

                    The QR data is readable and has a valid ReceiptShield
                    payload format.

                    However, this check does not currently verify the
                    issuer's identity or prove that the file has not
                    been modified after stamping.

                    The QR code could also have been copied into another file.

                    This document has NOT been cryptographically authenticated.
                """.trimIndent(),
                color = Color.rgb(245, 124, 0),
                isVerified = false
            )

        } catch (e: Exception) {
            failure(
                "Verification failed: ${e.message ?: "Unknown error"}"
            )
        }
    }

    private fun readFile(uri: Uri): ByteArray {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(
                "Unable to open the selected file."
            )

        return input.use { it.readBytes() }
    }

    private fun failure(message: String): VerificationOutcome {
        return VerificationOutcome(
            title = "VERIFICATION FAILED",
            message = message,
            color = Color.rgb(198, 40, 40),
            isVerified = false
        )
    }
}
