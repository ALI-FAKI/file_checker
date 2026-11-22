package com.ali.receiptshield.crypto

import org.json.JSONObject

const val RECEIPT_VERSION_V4 = "RS_V4"

enum class DocumentFormatV4 {
    PDF, DOCX, IMAGE, PNG, JPG, JPEG, WEBP, BMP, UNKNOWN;

    companion object {
        fun fromFileNameOrMime(fileName: String, mimeType: String?): DocumentFormatV4 {
            val name = fileName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return when {
                name.endsWith(".pdf") || mime.contains("pdf") -> PDF
                name.endsWith(".docx") || mime.contains("wordprocessingml") -> DOCX
                name.endsWith(".png") -> PNG
                name.endsWith(".jpg") -> JPG
                name.endsWith(".jpeg") -> JPEG
                name.endsWith(".webp") -> WEBP
                name.endsWith(".bmp") -> BMP
                else -> UNKNOWN
            }
        }
    }
}

enum class StampModeV4 {
    EMBEDDED, 
    DETACHED, 
    QR_ONLY, 
    QR_AND_SIGNATURE,
    SIGNATURE_ONLY
}

enum class InjectedArtifactType {
    METADATA_STREAM, 
    SIGNATURE_IMAGE, 
    QR_IMAGE, 
    RELATIONSHIP, 
    CONTENT_TYPE, 
    MANIFEST, 
    QR, 
    SIGNATURE, 
    UNKNOWN
}

enum class VerificationStatus {
    VERIFIED,
    AUTHENTIC,
    RECOMPRESSED,
    TAMPERED,
    DOCUMENT_TAMPERED,
    UNSUPPORTED_OR_CORRUPTED_FILE,
    INTEGRITY_MANIFEST_MISSING_OR_INVALID,
    SIGNATURE_ONLY_NO_CRYPTO_PROOF,
    INVALID_DIGITAL_SIGNATURE,
    QR_DOES_NOT_BELONG_TO_DOCUMENT,
    SIGNATURE_VALID_ISSUER_UNTRUSTED
}

data class PlacementMetaV4(
    val pageIndex: Int,
    val xRatio: Double,
    val yRatio: Double,
    val widthRatio: Double,
    val heightRatio: Double
)

data class SignatureMetaV4(
    val placement: PlacementMetaV4,
    val signatureDigestSha256: String
)

data class InjectedArtifactV4(
    val type: InjectedArtifactType,
    val identifier: String,
    val digestSha256: String
)

data class IntegrityManifestV4(
    val version: String = RECEIPT_VERSION_V4,
    val receiptId: String,
    val createdAtMillis: Long,
    val documentFormat: DocumentFormatV4,
    val stampMode: StampModeV4,
    val originalDocSha256: String,
    val issuerId: String,
    val originalPerceptualHash: String = "",
    val qrPlacement: PlacementMetaV4? = null,
    val signatureMeta: SignatureMetaV4? = null,
    val injectedArtifacts: List<InjectedArtifactV4> = emptyList()
)

data class ReceiptPayloadV4(
    val version: String = RECEIPT_VERSION_V4,
    val receiptId: String,
    val issuerId: String,
    val publicKeyBase64: String,
    val timestampMillis: Long,
    val nonce: String,
    val documentFormat: DocumentFormatV4,
    val stampMode: StampModeV4,
    val integrityManifest: IntegrityManifestV4,
    val digitalSignatureBase64: String = ""
) {
    fun toQrJsonString(): String {
        return JSONObject().apply {
            put("version", version)
            put("receiptId", receiptId)
            put("issuerId", issuerId)
            put("publicKeyBase64", publicKeyBase64)
            put("timestampMillis", timestampMillis)
            put("nonce", nonce)
            put("documentFormat", documentFormat.name)
            put("stampMode", stampMode.name)
            put("digitalSignatureBase64", digitalSignatureBase64)
            put("integrityManifest", JSONObject().apply {
                put("receiptId", integrityManifest.receiptId)
                put("createdAtMillis", integrityManifest.createdAtMillis)
                put("documentFormat", integrityManifest.documentFormat.name)
                put("stampMode", integrityManifest.stampMode.name)
                put("originalDocSha256", integrityManifest.originalDocSha256)
                put("issuerId", integrityManifest.issuerId)
                put("originalPerceptualHash", integrityManifest.originalPerceptualHash)
            })
        }.toString()
    }
}
