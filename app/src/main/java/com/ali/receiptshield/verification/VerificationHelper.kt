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
    val status: VerificationStatus = VerificationStatus.UNSUPPORTED_OR_CORRUPTED_FILE,
    // FIX 2: Populated only when the failing check is "issuer not trusted".
    // Used by MainActivity to show the "Trust this issuer" button.
    val untrustedIssuerId: String? = null,
    val untrustedPublicKeyBase64: String? = null
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

            if (format == DocumentFormatV4.UNKNOWN) {
                return@withContext outcome(
                    status = VerificationStatus.UNSUPPORTED_OR_CORRUPTED_FILE,
                    title = "UNSUPPORTED OR CORRUPTED FILE",
                    message = "Unsupported file format for file: $fileName",
                    color = Color.rgb(198, 40, 40),
                    isVerified = false
                )
            }

            // 1. Extract embedded RS_V4 Integrity Manifest
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

            // 2. Extract and parse QR payload (OPTIMIZED USING PLACEMENT DATA)
            val qrContent: String? = when (format) {
                DocumentFormatV4.PDF -> {
                    documentStampHelper.extractQrFromPdf(fileBytes, manifest.qrPlacement)
                }
                DocumentFormatV4.DOCX -> {
                    documentStampHelper.extractQrFromDocx(fileBytes, RsV4IntegrityManager.DOCX_QR_ENTRY)
                }
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
                    
                    // Crop to the exact QR location for a reliable scan
                    val cropped = if (manifest.qrPlacement != null) {
                        documentStampHelper.cropBitmapToPlacement(bitmap, manifest.qrPlacement)
                    } else {
                        null
                    }

                    try {
                        securityManager.decodeQRCode(cropped ?: bitmap)
                    } finally {
                        if (cropped != null) cropped.recycle()
                        bitmap.recycle()
                    }
                }
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
                else -> false
            }

            if (!documentIntact) {
                // FIX 5: Make DOCX-specific failure honest about the known limitation
                val docxFailureNote = if (format == DocumentFormatV4.DOCX) {
                    "\n\nNote: DOCX integrity is best-effort. Microsoft Word and " +
                    "Google Docs re-serialize the XML on every save, which can " +
                    "invalidate this check even without intentional content changes."
                } else ""

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

                        RESULT: TAMPERING DETECTED.$docxFailureNote
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

                        Tap "Trust this issuer" below to add this public key to your
                        trusted list and re-verify.

                        RESULT: SIGNATURE VALID — ISSUER NOT TRUSTED.
                    """.trimIndent(),
                    color = Color.rgb(245, 124, 0),
                    isVerified = false,
                    // FIX 2: Return the data MainActivity needs to show the trust button
                    untrustedIssuerId = payload.issuerId,
                    untrustedPublicKeyBase64 = payload.publicKeyBase64
                )
            }

            // Build the VERIFIED message, with a DOCX-specific honesty note.
            val verifiedBase = """
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
            """.trimIndent()

            // FIX 5: Append DOCX-specific caveat when applicable
            val verifiedFinal = if (format == DocumentFormatV4.DOCX) {
                "$verifiedBase\n\nNote: DOCX integrity is best-effort. Re-saving this file " +
                "in Microsoft Word or Google Docs may invalidate the integrity check " +
                "due to XML re-serialization, even without content changes."
            } else {
                verifiedBase
            }

            return@withContext outcome(
                status = VerificationStatus.VERIFIED,
                title = "VERIFIED — TRUSTED ISSUER & INTEGRITY CONFIRMED",
                message = verifiedFinal,
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

    /**
     * Direct call to TrustedIssuerRegistry. The previous reflection-based
     * implementation could silently pick the wrong overload (`isTrusted(payload)`
     * instead of `isTrustedIssuer(issuerId, publicKey)`) because both names
     * matched the reflection filter, weakening the trust check.
     */
    private fun isIssuerTrusted(
        payload: ReceiptPayloadV4,
        registry: TrustedIssuerRegistry
    ): Boolean {
        return try {
            registry.isTrustedIssuer(
                issuerId = payload.issuerId,
                publicKeyBase64 = payload.publicKeyBase64
            )
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
        isVerified: Boolean,
        untrustedIssuerId: String? = null,
        untrustedPublicKeyBase64: String? = null
    ): VerificationOutcome {
        return VerificationOutcome(
            title = title,
            message = message,
            color = color,
            isVerified = isVerified,
            status = status,
            untrustedIssuerId = untrustedIssuerId,
            untrustedPublicKeyBase64 = untrustedPublicKeyBase64
        )
    }
}
