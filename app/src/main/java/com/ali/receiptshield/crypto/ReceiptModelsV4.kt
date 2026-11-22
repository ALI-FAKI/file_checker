package com.ali.receiptshield.crypto

// The Version Constant
const val RECEIPT_VERSION_V4 = "RS_V4"

enum class DocumentFormatV4 {
    PDF, DOCX, IMAGE, UNKNOWN
}

enum class StampModeV4 {
    EMBEDDED,   // QR is printed onto the document
    DETACHED    // QR is a separate image file
}

enum class InjectedArtifactType {
    QR_CODE,
    MANIFEST,
    SIGNATURE
}

enum class VerificationStatus {
    AUTHENTIC,
    RECOMPRESSED,
    TAMPERED,
    ERROR
}

data class PlacementMetaV4(
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class SignatureMetaV4(
    val algorithm: String, // e.g., "Ed25519"
    val keyId: String,
    val signatureBase64: String
)

data class InjectedArtifactV4(
    val type: InjectedArtifactType,
    val pathInsideDocument: String, // e.g., "word/media/qr.png"
    val hash: String
)

data class IntegrityManifestV4(
    val originalFileHash: String,
    val originalPerceptualHash: String,
    val injectedArtifacts: List<InjectedArtifactV4>
)

data class ReceiptPayloadV4(
    val version: String = RECEIPT_VERSION_V4,
    val issuerId: String,
    val timestamp: Long,
    val nonce: String,
    val format: DocumentFormatV4,
    val stampMode: StampModeV4,
    val manifest: IntegrityManifestV4,
    val placementMeta: PlacementMetaV4?,
    val signatureMeta: SignatureMetaV4?
) {
    // Serialize to JSON or pipe-separated string depending on your V4 manager
    fun serialize(): String {
        // This is a placeholder. Your RsV4IntegrityManager will handle the actual JSON serialization.
        return "$version|$issuerId|$timestamp|$nonce"
    }
}
