
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
                        return@withContext failure(
                            "Unsupported file format."
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

            val payload = ReceiptPayload.deserialize(qrContent)
                ?: return@withContext failure(
                    "A QR code was found, but its ReceiptShield data is invalid."
                )

            val issueDate = synchronized(dateFormatter) {
                dateFormatter.format(Date(payload.timestamp))
            }

            // Legacy payloads do not contain a digital signature.
            if (payload.version != "RS_V3") {
                return@withContext VerificationOutcome(
                    title = "LEGACY STAMP — NOT AUTHENTICATED",
                    message = """
                        A legacy ReceiptShield stamp was detected.

                        File: $fileName

                        Issuer ID in QR: ${payload.issuerId}

                        Recorded issue date: $issueDate

                        SECURITY NOTICE:

                        This stamp uses an older ReceiptShield format
                        without a cryptographic digital signature.

                        Its issuer identity cannot be authenticated,
                        and this check cannot establish whether the
                        document was modified after stamping.

                        This document has NOT been cryptographically authenticated.
                    """.trimIndent(),
                    color = Color.rgb(245, 124, 0),
                    isVerified = false
                )
            }

            // Verify the RS_V3 digital signature.
            val signatureValid = securityManager
                .verifyPayloadSignature(payload)

            if (!signatureValid) {
                return@withContext failure(
                    """
                    The ReceiptShield digital signature is INVALID.

                    File: $fileName

                    Issuer ID in QR: ${payload.issuerId}

                    Recorded issue date: $issueDate

                    The digital signature does not match the
                    signed QR payload.

                    The QR data may have been modified, corrupted,
                    or signed with a different private key.

                    Do not treat this document as authenticated.
                    """.trimIndent()
                )
            }

            // The signature is valid against the public key embedded
            // in the QR, but that key is not yet checked against a
            // trusted issuer registry.
            return@withContext VerificationOutcome(
                title = "DIGITAL SIGNATURE VALID — ISSUER UNCONFIRMED",
                message = """
                    The ReceiptShield digital signature is valid.

                    File: $fileName

                    Issuer ID in QR: ${payload.issuerId}

                    Recorded issue date: $issueDate

                    SIGNATURE RESULT:

                    The signed QR payload matches its digital signature.

                    IMPORTANT TRUST NOTICE:

                    The public key used for verification was supplied
                    inside the QR code itself.

                    ReceiptShield has not yet checked this key against
                    a trusted issuer registry.

                    Therefore, the signature is mathematically valid,
                    but the issuer's real-world identity is NOT confirmed.

                    This check also does not yet prove that the complete
                    document content has remained unchanged after stamping.

                    A copied QR code may still appear in another file.

                    RESULT: SIGNATURE VALID, ISSUER NOT YET TRUSTED.
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
        val input: InputStream =
            context.contentResolver.openInputStream(uri)
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
