package com.ali.receiptshield.verification

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.ali.receiptshield.crypto.ReceiptPayload // <-- FIX 1: ADDED THIS IMPORT
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
                return@withContext failure(
                    "The selected file is empty."
                )
            }

            val extension = fileName
                .substringAfterLast('.', "")
                .lowercase()

            val qrContent: String? = when (extension) {

                "pdf" -> {
                    documentStampHelper.extractQrFromPdf(fileBytes)
                }

                "docx" -> {
                    documentStampHelper.extractQrFromDocx(fileBytes)
                }

                "doc" -> {
                    return@withContext failure(
                        "Legacy DOC files are not supported. " +
                        "Please convert the document to DOCX first."
                    )
                }

                else -> {
                    val bitmap = BitmapFactory.decodeByteArray(
                        fileBytes,
                        0,
                        fileBytes.size
                    )

                    if (bitmap == null) {
                        return@withContext failure(
                            "This file format is not supported for QR verification."
                        )
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

            // FIX 2: Call deserialize on ReceiptPayload instead of securityManager
            val payload = ReceiptPayload.deserialize(qrContent)

            if (payload == null) {
                return@withContext failure(
                    "A QR code was found, but its ReceiptShield data is invalid."
                )
            }

            val issueDate = dateFormatter.format(
                Date(payload.timestamp)
            )

            /*
             * Important:
             * PDF and DOCX files are modified when the QR code is inserted.
             * Their final file hash is therefore different from the original
             * hash stored in the QR payload.
             *
             * This check confirms that a readable ReceiptShield QR payload
             * exists. It does NOT prove that the document contents are
             * unchanged or that the QR code is genuine.
             */

            if (extension == "pdf" || extension == "docx") {
                return@withContext VerificationOutcome(
                    title = "QR CODE FOUND",
                    message = """
                        A ReceiptShield QR code was detected.

                        File: $fileName

                        Issuer ID: ${payload.issuerId}

                        Issued: $issueDate

                        Document QR detection: Successful

                        Integrity status: Not independently verified.

                        The QR code was found, but this result does not prove that the document has not been modified.
                    """.trimIndent(),
                    color = Color.rgb(230, 126, 34),
                    isVerified = false
                )
            }

            // Image verification: compare the actual file hash.
            val actualHash = securityManager.hashInputStream(
                fileBytes.inputStream()
            )

            if (actualHash.equals(payload.fileHash, ignoreCase = true)) {
                return@withContext VerificationOutcome(
                    title = "DOCUMENT VERIFIED",
                    message = """
                        The ReceiptShield QR code was detected.

                        File: $fileName

                        Issuer ID: ${payload.issuerId}

                        Issued: $issueDate

                        Integrity check: Exact file hash match.

                        The file matches the hash stored in the QR payload.
                    """.trimIndent(),
                    color = Color.rgb(46, 125, 50),
                    isVerified = true
                )
            }

            // If exact hash differs, try perceptual image comparison.
            val bitmap = BitmapFactory.decodeByteArray(
                fileBytes,
                0,
                fileBytes.size
            )

            if (bitmap != null) {
                try {
                    val actualPerceptualHash =
                        securityManager.computePerceptualHash(bitmap)

                    if (
                        payload.perceptualHash.isNotBlank() &&
                        securityManager.isVisuallySimilar(
                            payload.perceptualHash,
                            actualPerceptualHash
                        )
                    ) {
                        return@withContext VerificationOutcome(
                            title = "VISUALLY SIMILAR",
                            message = """
                                The QR code was detected.

                                File: $fileName

                                Issuer ID: ${payload.issuerId}

                                Issued: $issueDate

                                The image looks visually similar to the original, but its exact file hash has changed.

                                Further review is recommended.
                            """.trimIndent(),
                            color = Color.rgb(230, 126, 34),
                            isVerified = false
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            failure(
                "The file hash does not match the original record. The file may have been modified."
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

        return input.use {
            it.readBytes()
        }
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
