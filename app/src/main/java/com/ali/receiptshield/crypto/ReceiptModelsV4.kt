package com.ali.receiptshield.crypto

import org.json.JSONObject

const val RECEIPT_VERSION_V4 = "RS_V4"

enum class DocumentFormatV4(val wireName: String) {
    PDF("PDF"), DOCX("DOCX"), PNG("PNG"), JPG("JPG"), JPEG("JPEG"), WEBP("WEBP"), BMP("BMP"), UNKNOWN("UNKNOWN");

    companion object {
        fun fromFileNameOrMime(fileName: String, mimeType: String?): DocumentFormatV4? {
            val name = fileName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return when {
                name.endsWith(".pdf") || mime.contains("pdf") -> PDF
                name.endsWith(".docx") || mime.contains("wordprocessingml") -> DOCX
                name.endsWith(".png") || mime.contains("png") -> PNG
                name.endsWith(".jpg") || mime.contains("jpeg") -> JPG
                name.endsWith(".jpeg") -> JPEG
                name.endsWith(".webp") || mime.contains("webp") -> WEBP
                name.endsWith(".bmp") || mime.contains("bmp") -> BMP
                else -> null
            }
        }
    }
}

enum class StampModeV4 {
    EMBEDDED, DETACHED, SIGNATURE_ONLY
}

enum class VerificationStatus {
    VERIFIED,
    UNSUPPORTED_OR_CORRUPTED_FILE,
    INTEGRITY_MANIFEST_MISSING_OR_INVALID,
    SIGNATURE_ONLY_NO_CRYPTO_PROOF,
    INVALID_DIGITAL_SIGNATURE,
    QR_DOES_NOT_BELONG_TO_DOCUMENT,
    DOCUMENT_TAMPERED,
    SIGNATURE_VALID_ISSUER_UNTRUSTED
}

data class IntegrityManifestV4(
    val version: String = RECEIPT_VERSION_V4,
    val receiptId: String,
    val createdAtMillis: Long,
    val stampMode: StampModeV4,
    val originalFileHash: String,
    val originalPerceptualHash: String
)

data class ReceiptPayloadV4(
    val version: String = RECEIPT_VERSION_V4,
    val receiptId: String,
    val issuerId: String,
    val publicKeyBase64: String,
    val timestampMillis: Long,
    val documentFormat: DocumentFormatV4,
    val stampMode: StampModeV4,
    val integrityManifest: IntegrityManifestV4,
    val signatureBase64: String
) {
    fun serialize(): String {
        return JSONObject().apply {
            put("version", version)
            put("receiptId", receiptId)
            put("issuerId", issuerId)
            put("publicKeyBase64", publicKeyBase64)
            put("timestampMillis", timestampMillis)
            put("documentFormat", documentFormat.name)
            put("stampMode", stampMode.name)
            put("signatureBase64", signatureBase64)
            put("integrityManifest", JSONObject().apply {
                put("version", integrityManifest.version)
                put("receiptId", integrityManifest.receiptId)
                put("createdAtMillis", integrityManifest.createdAtMillis)
                put("stampMode", integrityManifest.stampMode.name)
                put("originalFileHash", integrityManifest.originalFileHash)
                put("originalPerceptualHash", integrityManifest.originalPerceptualHash)
            })
        }.toString()
    }

    companion object {
        fun fromQrJsonString(raw: String): ReceiptPayloadV4? {
            return try {
                val json = JSONObject(raw)
                val manifestJson = json.getJSONObject("integrityManifest")
                ReceiptPayloadV4(
                    version = json.getString("version"),
                    receiptId = json.getString("receiptId"),
                    issuerId = json.getString("issuerId"),
                    publicKeyBase64 = json.getString("publicKeyBase64"),
                    timestampMillis = json.getLong("timestampMillis"),
                    documentFormat = DocumentFormatV4.valueOf(json.getString("documentFormat")),
                    stampMode = StampModeV4.valueOf(json.getString("stampMode")),
                    signatureBase64 = json.getString("signatureBase64"),
                    integrityManifest = IntegrityManifestV4(
                        version = manifestJson.getString("version"),
                        receiptId = manifestJson.getString("receiptId"),
                        createdAtMillis = manifestJson.getLong("createdAtMillis"),
                        stampMode = StampModeV4.valueOf(manifestJson.getString("stampMode")),
                        originalFileHash = manifestJson.getString("originalFileHash"),
                        originalPerceptualHash = manifestJson.getString("originalPerceptualHash")
                    )
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
