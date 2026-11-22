
package com.ali.receiptshield.verification

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.ali.receiptshield.crypto.ReceiptPayload
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.crypto.TrustedIssuerRegistry
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

    private val trustedIssuerRegistry =
        TrustedIssuerRegistry(context.applicationContext)

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

            // Legacy stamps do not contain digital signatures.
            if (payload.version != "RS_V3") {
                return@withContext VerificationOutcome(
                    title = "LEGACY STAMP — NOT AUTHENTICATED",
                    message = """
                        A legacy ReceiptShield stamp was detected.

                        File: $fileName

                        Issuer ID: ${payload.issuerId}

                        Recorded issue date: $issueDate

                        SECURITY NOTICE:

                        This stamp does not contain a cryptographic
                        digital signature.

                        ReceiptShield cannot authenticate the issuer
                        or establish whether the document was modified.

                        RESULT: NOT VERIFIED.
                    """.trimIndent(),
                    color = Color.rgb(245, 124, 0),
                    isVerified = false
                )
            }

            // First verify the cryptographic signature.
            val signatureValid =
                securityManager.verifyPayloadSignature(payload)

            if (!signatureValid) {
                return@withContext failure(
                    """
                    INVALID DIGITAL SIGNATURE

                    File: $fileName

                    Issuer ID: ${payload.issuerId}

                    Recorded issue date: $issueDate

                    The digital signature does not match
                    the signed QR payload.

                    The QR data may have been modified,
                    corrupted, or signed using another private key.

                    RESULT: VERIFICATION FAILED.
                    """.trimIndent()
                )
            }

            // Then check whether the exact issuer ID and public key
            // are registered in the trusted local issuer registry.
            val issuerTrusted =
                trustedIssuerRegistry.isTrusted(payload)

            if (!issuerTrusted) {
                return@withContext VerificationOutcome(
                    title = "SIGNATURE VALID — ISSUER UNCONFIRMED",
                    message = """
                        The ReceiptShield digital signature is valid.

                        File: $fileName

                        Issuer ID: ${payload.issuerId}

                        Recorded issue date: $issueDate

                        SIGNATURE RESULT:

                        The QR payload matches its digital signature.

                        TRUST RESULT:

                        This issuer and public key are not registered
                        in this device's trusted issuer registry.

                        The public key was supplied inside the QR code
                        and has not been independently authenticated.

                        Do not treat the issuer as confirmed.

                        RESULT: SIGNATURE VALID, ISSUER NOT TRUSTED.
                    """.trimIndent(),
                    color = Color.rgb(245, 124, 0),
                    isVerified = false
                )
            }

            // Both signature and locally registered issuer are valid.
            return@withContext VerificationOutcome(
                title = "TRUSTED ISSUER — SIGNATURE VALID",
                message = """
                    ReceiptShield verification successful.

                    File: $fileName

                    Issuer ID: ${payload.issuerId}

                    Recorded issue date: $issueDate

                    SECURITY RESULTS:

                    Digital signature: VALID

                    Trusted issuer: CONFIRMED

                    Public key: MATCHES REGISTERED ISSUER

                    This device recognizes the issuer's public key
                    as a trusted registered key.

                    IMPORTANT:

                    This verification confirms the signed QR payload
                    and registered issuer key.

                    It does not yet prove that the complete document
                    content has remained unchanged after stamping.

                    A copied QR code may still appear in another file.

                    RESULT: TRUSTED ISSUER SIGNATURE VERIFIED.
                """.trimIndent(),
                color = Color.rgb(46, 125, 50),
                isVerified = true
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
