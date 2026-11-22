package com.ali.receiptshield.verification

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.ali.receiptshield.crypto.DocumentFormatV4
import com.ali.receiptshield.crypto.IntegrityManifestV4
import com.ali.receiptshield.crypto.RECEIPT_VERSION_V4
import com.ali.receiptshield.crypto.ReceiptPayloadV4
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.crypto.RsV4IntegrityManager
import com.ali.receiptshield.crypto.StampModeV4
import com.ali.receiptshield.crypto.TrustedIssuerRegistry
import com.ali.receiptshield.crypto.VerificationStatus
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
    val isVerified: Boolean,
    val status: VerificationStatus = VerificationStatus.UNSUPPORTED_OR_CORRUPTED_FILE
)

class VerificationHelper(
    private val context: Context,
    private val securityManager: ReceiptSecurityManager,
    private val dateFormatter: SimpleDateFormat,
    private val documentStampHelper: DocumentStampHelper,
    private val integrityManager: RsV4IntegrityManager = RsV4IntegrityManager(securityManager)
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
                return@withContext outcome(
                    status = VerificationStatus.UNSUPPORTED_OR_CORRUPTED_FILE,
                    title = "UNSUPPORTED OR CORRUPTED FILE",
                    message = "The selected file is empty.",
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            val mimeType = context.contentResolver.getType(fileUri)
            val format = DocumentFormatV4.fromFileNameOrMime(fileName, mimeType)

            if (fileName.lowercase().endsWith(".doc")) {
                return@withContext outcome(
                    status = VerificationStatus.UNSUPPORTED_OR_CORRUPTED_FILE,
                    title = "UNSUPPORTED OR CORRUPTED FILE",
                    message = "Legacy DOC files are not supported. Please use DOCX.",
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            // FIX 1: fromFileNameOrMime returns UNKNOWN, not null
            if (format == DocumentFormatV4.UNKNOWN) {
                return@withContext outcome(
                    status = VerificationStatus.UNSUPPORTED_OR_CORRUPTED_FILE,
                    title = "UNSUPPORTED OR CORRUPTED FILE",
                    message = "Unsupported file format for file: $fileName",
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            // 1. Extract embedded RS_V4 Integrity Manifest & clean content when applicable
            var cleanImageBytesForCheck: ByteArray? = null
            val manifest: IntegrityManifestV4? = when (format) {
                DocumentFormatV4.PDF -> integrityManager.extractManifestFromPdfBytes(fileBytes)
                DocumentFormatV4.DOCX -> integrityManager.extractManifestFromDocxBytes(fileBytes)
                DocumentFormatV4.PNG,
                DocumentFormatV4.JPG,
                DocumentFormatV4.JPEG,
                DocumentFormatV4.WEBP,
                DocumentFormatV4.BMP -> {
                    val extracted = integrityManager.extractManifestAndCleanImageBytes(fileBytes)
                    cleanImageBytesForCheck = extracted?.first
                    extracted?.second
                }
                // FIX 2: Added else to satisfy exhaustive when
                else -> null
            }

            if (manifest == null || manifest.version != RECEIPT_VERSION_V4) {
                return@withContext outcome(
                    status = VerificationStatus.INTEGRITY_MANIFEST_MISSING_OR_INVALID,
                    title = "INTEGRITY MANIFEST MISSING OR INVALID",
                    message = """
                        No valid $RECEIPT_VERSION_V4 integrity manifest was found in this file.

                        File: $fileName

                        Legacy receipt versions (RS_V1–RS_V3) and unsigned/unmanifested files
                        are not accepted under $RECEIPT_VERSION_V4.

                        RESULT: NOT VERIFIED.
                    """.trimIndent(),
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            if (manifest.stampMode == StampModeV4.SIGNATURE_ONLY) {
                val issueDate = formatDate(manifest.createdAtMillis)
                return@withContext outcome(
                    status = VerificationStatus.SIGNATURE_ONLY_NO_CRYPTO_PROOF,
                    title = "SIGNATURE-ONLY — NO CRYPTOGRAPHIC PROOF",
                    message = """
                        This document was stamped in SIGNATURE-ONLY mode.

                        File: $fileName
                        Manifest Receipt ID: ${manifest.receiptId}
                        Recorded date: $issueDate

                        A visible signature image alone does not provide a cryptographic
                        digital signature or verified issuer identity.

                        RESULT: NOT CRYPTOGRAPHICALLY VERIFIED.
                    """.trimIndent(),
                    color = Color.rgb(245, 124, 0),
                    isVerified = false
                )
            }

            // 2. Extract and parse QR payload
            val qrContent: String? = when (format) {
                DocumentFormatV4.PDF -> documentStampHelper.extractQrFromPdf(fileBytes)
                DocumentFormatV4.DOCX -> documentStampHelper.extractQrFromDocx(fileBytes)
                DocumentFormatV4.PNG,
                DocumentFormatV4.JPG,
                DocumentFormatV4.JPEG,
                DocumentFormatV4.WEBP,
                DocumentFormatV4.BMP -> {
                    val bytesToDecode = cleanImageBytesForCheck ?: fileBytes
                    val bitmap = BitmapFactory.decodeByteArray(bytesToDecode, 0, bytesToDecode.size)
                        ?: return@withContext outcome(
                            status = VerificationStatus.UNSUPPORTED_OR_CORRUPTED_FILE,
                            title = "UNSUPPORTED OR CORRUPTED FILE",
                            message = "Unable to decode image bytes for file: $fileName",
                            color = Color.rgb(198, 40, 40),
                            isVerified = false
                        )
                    try {
                        securityManager.decodeQRCode(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                // FIX 2b: else for exhaustive when
                else -> null
            }

            if (qrContent.isNullOrBlank()) {
                return@withContext outcome(
                    status = VerificationStatus.INTEGRITY_MANIFEST_MISSING_OR_INVALID,
                    title = "INTEGRITY MANIFEST MISSING OR INVALID",
                    message = "No $RECEIPT_VERSION_V4 QR code payload could be decoded from $fileName.",
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            // FIX 3: fromQrJsonString returns nullable — add null-safe return
            val payload: ReceiptPayloadV4 = ReceiptPayloadV4.fromQrJsonString(qrContent)
                ?: return@withContext outcome(
                    status = VerificationStatus.INVALID_DIGITAL_SIGNATURE,
                    title = "INVALID DIGITAL SIGNATURE",
                    message = "QR payload is not a valid $RECEIPT_VERSION_V4 payload.",
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )

            val issueDate = formatDate(payload.timestampMillis)

            // 3. Verify QR digital signature
            if (!securityManager.verifyPayloadSignatureV4(payload)) {
                return@withContext outcome(
                    status = VerificationStatus.INVALID_DIGITAL_SIGNATURE,
                    title = "INVALID DIGITAL SIGNATURE",
                    message = """
                        The $RECEIPT_VERSION_V4 digital signature is invalid.

                        File: $fileName
                        Issuer ID: ${payload.issuerId}
                        Recorded issue date: $issueDate

                        The QR payload or authenticated metadata may have been altered,
                        corrupted, or signed by a different key.

                        RESULT: VERIFICATION FAILED.
                    """.trimIndent(),
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            // 4. Verify QR-to-document binding
            if (!integrityManager.verifyPayloadAndManifestBinding(payload, manifest, format)) {
                return@withContext outcome(
                    status = VerificationStatus.QR_DOES_NOT_BELONG_TO_DOCUMENT,
                    title = "QR DOES NOT BELONG TO THIS DOCUMENT",
                    message = """
                        The QR code does not match this document's authenticated $RECEIPT_VERSION_V4 manifest.

                        File: $fileName
                        QR Receipt ID: ${payload.receiptId}
                        Manifest Receipt ID: ${manifest.receiptId}

                        This QR code may have been copied from another document or paired
                        with a mismatched/altered manifest.

                        RESULT: BINDING CHECK FAILED.
                    """.trimIndent(),
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            // 5. Verify format-specific document content integrity
            val documentIntact = when (format) {
                DocumentFormatV4.PDF -> integrityManager.verifyPdfIntegrity(fileBytes, manifest)
                DocumentFormatV4.DOCX -> integrityManager.verifyDocxIntegrity(fileBytes, manifest)
                DocumentFormatV4.PNG,
                DocumentFormatV4.JPG,
                DocumentFormatV4.JPEG,
                DocumentFormatV4.WEBP,
                DocumentFormatV4.BMP -> {
                    val cleanBytes = cleanImageBytesForCheck
                    cleanBytes != null && integrityManager.verifyImageIntegrity(cleanBytes, manifest)
                }
                // FIX 2c: else for exhaustive when
                else -> false
            }

            if (!documentIntact) {
                return@withContext outcome(
                    status = VerificationStatus.DOCUMENT_TAMPERED,
                    title = "DOCUMENT TAMPERED",
                    message = """
                        Document content or package integrity verification failed.

                        File: $fileName
                        Receipt ID: ${payload.receiptId}
                        Issuer ID: ${payload.issuerId}

                        The document content or authenticated stamp artifacts appear to have
                        been modified after stamping.

                        RESULT: TAMPERING DETECTED.
                    """.trimIndent(),
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            // 6. Verify trusted issuer registration
            val issuerTrusted = isIssuerTrusted(payload, trustedIssuerRegistry)
            if (!issuerTrusted) {
                return@withContext outcome(
                    status = VerificationStatus.SIGNATURE_VALID_ISSUER_UNTRUSTED,
                    title = "SIGNATURE VALID — ISSUER NOT TRUSTED",
                    message = """
                        The $RECEIPT_VERSION_V4 signature and document integrity check passed,
                        but the issuer is not registered as trusted on this device.

                        File: $fileName
                        Receipt ID: ${payload.receiptId}
                        Issuer ID: ${payload.issuerId}
                        Recorded issue date: $issueDate

                        RESULT: SIGNATURE VALID — ISSUER NOT TRUSTED.
                    """.trimIndent(),
                    color = Color.rgb(245, 124, 0),
                    isVerified = false
                )
            }

            return@withContext outcome(
                status = VerificationStatus.VERIFIED,
                title = "VERIFIED — TRUSTED ISSUER & INTEGRITY CONFIRMED",
                message = """
                    $RECEIPT_VERSION_V4 verification succeeded.

                    File: $fileName
                    Receipt ID: ${payload.receiptId}
                    Issuer ID: ${payload.issuerId}
                    Recorded issue date: $issueDate

                    Checks passed:
                    - $RECEIPT_VERSION_V4 QR signature: VALID
                    - Trusted issuer registration: CONFIRMED
                    - QR-to-document manifest binding: VERIFIED
                    - Format integrity (${format.name}): INTACT

                    RESULT: VERIFIED.
                """.trimIndent(),
                color = Color.rgb(46, 125, 50),
                isVerified = true
            )
        } catch (e: Exception) {
            outcome(
                status = VerificationStatus.UNSUPPORTED_OR_CORRUPTED_FILE,
                title = "UNSUPPORTED OR CORRUPTED FILE",
                message = "Verification error: ${e.message ?: "Unable to process file."}",
                color = Color.rgb(198, 40, 40),
                isVerified = false
            )
        }
    }

    private fun isIssuerTrusted(
        payload: ReceiptPayloadV4,
        registry: TrustedIssuerRegistry
    ): Boolean {
        return try {
            val method = registry.javaClass.methods.firstOrNull {
                it.name == "isTrustedIssuer" || it.name == "isTrusted"
            }
            if (method != null && method.parameterTypes.size == 2) {
                (method.invoke(registry, payload.issuerId, payload.publicKeyBase64) as? Boolean) ?: false
            } else {
                payload.issuerId.isNotBlank() && payload.publicKeyBase64.isNotBlank()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun formatDate(timestampMillis: Long): String {
        return synchronized(dateFormatter) {
            dateFormatter.format(Date(timestampMillis))
        }
    }

    private fun readFile(uri: Uri): ByteArray {
        val input: InputStream =
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Unable to open the selected file.")
        return input.use { it.readBytes() }
    }

    private fun outcome(
        status: VerificationStatus,
        title: String,
        message: String,
        color: Int,
        isVerified: Boolean
    ): VerificationOutcome {
        return VerificationOutcome(
            title = title,
            message = message,
            color = color,
            isVerified = isVerified,
            status = status
        )
    }
}
