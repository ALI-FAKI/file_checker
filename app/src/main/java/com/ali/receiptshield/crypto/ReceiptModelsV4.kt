package com.ali.receiptshield.crypto

import org.json.JSONArray
import org.json.JSONObject

const val RECEIPT_VERSION_V4 = "RS_V4"

enum class DocumentFormatV4(val wireName: String) {
    PDF("PDF"),
    DOCX("DOCX"),
    PNG("PNG"),
    JPG("JPG"),
    JPEG("JPEG"),
    WEBP("WEBP"),
    BMP("BMP");

    companion object {
        fun fromFileNameOrMime(fileName: String, mimeType: String?): DocumentFormatV4? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when {
                ext == "pdf" || mimeType == "application/pdf" -> PDF
                ext == "docx" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
                ext == "png" || mimeType == "image/png" -> PNG
                ext == "jpg" || mimeType == "image/jpeg" -> JPG
                ext == "jpeg" -> JPEG
                ext == "webp" || mimeType == "image/webp" -> WEBP
                ext == "bmp" || mimeType == "image/bmp" -> BMP
                else -> null
            }
        }
    }
}

enum class StampModeV4 {
    QR_ONLY,
    SIGNATURE_ONLY,
    QR_AND_SIGNATURE
}

enum class InjectedArtifactType {
    QR_IMAGE,
    SIGNATURE_IMAGE,
    MANIFEST,
    RELATIONSHIP,
    CONTENT_TYPE,
    METADATA_STREAM
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

data class PlacementMetaV4(
    val pageIndex: Int,
    val xRatio: Double,
    val yRatio: Double,
    val widthRatio: Double,
    val heightRatio: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pageIndex", pageIndex)
        put("xRatio", xRatio)
        put("yRatio", yRatio)
        put("widthRatio", widthRatio)
        put("heightRatio", heightRatio)
    }

    companion object {
        fun fromJson(json: JSONObject) = PlacementMetaV4(
            pageIndex = json.getInt("pageIndex"),
            xRatio = json.getDouble("xRatio"),
            yRatio = json.getDouble("yRatio"),
            widthRatio = json.getDouble("widthRatio"),
            heightRatio = json.getDouble("heightRatio")
        )
    }
}

data class SignatureMetaV4(
    val placement: PlacementMetaV4,
    val signatureDigestSha256: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("placement", placement.toJson())
        put("signatureDigestSha256", signatureDigestSha256)
    }

    companion object {
        fun fromJson(json: JSONObject) = SignatureMetaV4(
            placement = PlacementMetaV4.fromJson(json.getJSONObject("placement")),
            signatureDigestSha256 = json.getString("signatureDigestSha256")
        )
    }
}

data class InjectedArtifactV4(
    val type: InjectedArtifactType,
    val identifier: String,
    val digestSha256: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("identifier", identifier)
        put("digestSha256", digestSha256)
    }

    companion object {
        fun fromJson(json: JSONObject) = InjectedArtifactV4(
            type = InjectedArtifactType.valueOf(json.getString("type")),
            identifier = json.getString("identifier"),
            digestSha256 = json.getString("digestSha256")
        )
    }
}

data class IntegrityManifestV4(
    val version: String = RECEIPT_VERSION_V4,
    val receiptId: String,
    val documentFormat: DocumentFormatV4,
    val stampMode: StampModeV4,
    val originalDocSha256: String,
    val issuerId: String,
    val createdAtMillis: Long,
    val qrPlacement: PlacementMetaV4?,
    val signatureMeta: SignatureMetaV4?,
    val injectedArtifacts: List<InjectedArtifactV4>
) {
    fun toCanonicalBytes(): ByteArray = toJsonString().toByteArray(Charsets.UTF_8)

    private fun toJsonString(): String = JSONObject().apply {
        put("version", version)
        put("receiptId", receiptId)
        put("documentFormat", documentFormat.name)
        put("stampMode", stampMode.name)
        put("originalDocSha256", originalDocSha256)
        put("issuerId", issuerId)
        put("createdAtMillis", createdAtMillis)
        put("qrPlacement", qrPlacement?.toJson() ?: JSONObject.NULL)
        put("signatureMeta", signatureMeta?.toJson() ?: JSONObject.NULL)
        val arr = JSONArray()
        for (a in injectedArtifacts) arr.put(a.toJson())
        put("injectedArtifacts", arr)
    }.toString()

    companion object {
        fun fromJsonString(json: String): IntegrityManifestV4 {
            val o = JSONObject(json)
            val arr = o.getJSONArray("injectedArtifacts")
            return IntegrityManifestV4(
                version = o.getString("version"),
                receiptId = o.getString("receiptId"),
                documentFormat = DocumentFormatV4.valueOf(o.getString("documentFormat")),
                stampMode = StampModeV4.valueOf(o.getString("stampMode")),
                originalDocSha256 = o.getString("originalDocSha256"),
                issuerId = o.getString("issuerId"),
                createdAtMillis = o.getLong("createdAtMillis"),
                qrPlacement = if (o.isNull("qrPlacement")) null else PlacementMetaV4.fromJson(o.getJSONObject("qrPlacement")),
                signatureMeta = if (o.isNull("signatureMeta")) null else SignatureMetaV4.fromJson(o.getJSONObject("signatureMeta")),
                injectedArtifacts = (0 until arr.length()).map { InjectedArtifactV4.fromJson(arr.getJSONObject(it)) }
            )
        }
    }
}

data class ReceiptPayloadV4(
    val version: String = RECEIPT_VERSION_V4,
    val receiptId: String,
    val documentFormat: DocumentFormatV4,
    val originalDocSha256: String,
    val manifestSha256: String,
    val issuerId: String,
    val timestampMillis: Long,
    val nonce: String,
    val qrPlacement: PlacementMetaV4?,
    val signatureMeta: SignatureMetaV4?,
    val publicKeyBase64: String,
    val digitalSignatureBase64: String
) {
    fun toQrJsonString(): String = JSONObject().apply {
        put("version", version)
        put("receiptId", receiptId)
        put("documentFormat", documentFormat.name)
        put("originalDocSha256", originalDocSha256)
        put("manifestSha256", manifestSha256)
        put("issuerId", issuerId)
        put("timestampMillis", timestampMillis)
        put("nonce", nonce)
        put("qrPlacement", qrPlacement?.toJson() ?: JSONObject.NULL)
        put("signatureMeta", signatureMeta?.toJson() ?: JSONObject.NULL)
        put("publicKeyBase64", publicKeyBase64)
        put("digitalSignatureBase64", digitalSignatureBase64)
    }.toString()

    fun toCanonicalSigningBytes(): ByteArray = JSONObject().apply {
        put("version", version)
        put("receiptId", receiptId)
        put("documentFormat", documentFormat.name)
        put("originalDocSha256", originalDocSha256)
        put("manifestSha256", manifestSha256)
        put("issuerId", issuerId)
        put("timestampMillis", timestampMillis)
        put("nonce", nonce)
        put("qrPlacement", qrPlacement?.toJson() ?: JSONObject.NULL)
        put("signatureMeta", signatureMeta?.toJson() ?: JSONObject.NULL)
        put("publicKeyBase64", publicKeyBase64)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromQrJsonString(json: String): ReceiptPayloadV4 {
            val o = JSONObject(json)
            return ReceiptPayloadV4(
                version = o.getString("version"),
                receiptId = o.getString("receiptId"),
                documentFormat = DocumentFormatV4.valueOf(o.getString("documentFormat")),
                originalDocSha256 = o.getString("originalDocSha256"),
                manifestSha256 = o.getString("manifestSha256"),
                issuerId = o.getString("issuerId"),
                timestampMillis = o.getLong("timestampMillis"),
                nonce = o.getString("nonce"),
                qrPlacement = if (o.isNull("qrPlacement")) null else PlacementMetaV4.fromJson(o.getJSONObject("qrPlacement")),
                signatureMeta = if (o.isNull("signatureMeta")) null else SignatureMetaV4.fromJson(o.getJSONObject("signatureMeta")),
                publicKeyBase64 = o.getString("publicKeyBase64"),
                digitalSignatureBase64 = o.getString("digitalSignatureBase64")
            )
        }
    }
}
