
package com.ali.receiptshield.crypto

import org.json.JSONArray
import org.json.JSONObject

const val RECEIPT_VERSION_V4 = "RS_V4"

enum class DocumentFormatV4(val wireName: String) {
    PDF("PDF"),
    DOCX("DOCX"),
    IMAGE("IMAGE"),
    PNG("PNG"),
    JPG("JPG"),
    JPEG("JPEG"),
    WEBP("WEBP"),
    BMP("BMP"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromFileNameOrMime(
            fileName: String,
            mimeType: String?
        ): DocumentFormatV4 {
            val name = fileName.lowercase()
            val mime = mimeType?.lowercase() ?: ""

            return when {
                name.endsWith(".pdf") || mime.contains("pdf") -> PDF
                name.endsWith(".docx") || mime.contains("wordprocessingml") -> DOCX
                name.endsWith(".png") || mime.contains("png") -> PNG
                name.endsWith(".jpg") -> JPG
                name.endsWith(".jpeg") || mime.contains("jpeg") -> JPEG
                name.endsWith(".webp") || mime.contains("webp") -> WEBP
                name.endsWith(".bmp") || mime.contains("bmp") -> BMP
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
    val injectedArtifacts: List<InjectedArtifactV4> = emptyList(),

    // RSA signature over the completed manifest.
    // This field is deliberately excluded from its own signing bytes.
    val finalManifestSignatureBase64: String = ""
) {

    /**
     * Complete serialized manifest, including its final signature.
     */
    fun toCanonicalBytes(): ByteArray {
        return toJsonString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Canonical bytes used when signing the completed manifest.
     *
     * The signature field is excluded to prevent circular signing.
     * All other manifest fields, including every artifact, are included.
     */
    fun toFinalManifestSigningBytes(): ByteArray {
        return toJsonString(includeFinalManifestSignature = false)
            .toByteArray(Charsets.UTF_8)
    }

    fun toJsonString(
        includeFinalManifestSignature: Boolean = true
    ): String {
        return JSONObject().apply {
            put("version", version)
            put("receiptId", receiptId)
            put("createdAtMillis", createdAtMillis)
            put("documentFormat", documentFormat.name)
            put("stampMode", stampMode.name)
            put("originalDocSha256", originalDocSha256)
            put("issuerId", issuerId)
            put("originalPerceptualHash", originalPerceptualHash)

            qrPlacement?.let {
                put("qrPlacement", JSONObject().apply {
                    put("pageIndex", it.pageIndex)
                    put("xRatio", it.xRatio)
                    put("yRatio", it.yRatio)
                    put("widthRatio", it.widthRatio)
                    put("heightRatio", it.heightRatio)
                })
            }

            signatureMeta?.let {
                put("signatureMeta", JSONObject().apply {
                    put("signatureDigestSha256", it.signatureDigestSha256)
                    put("placement", JSONObject().apply {
                        put("pageIndex", it.placement.pageIndex)
                        put("xRatio", it.placement.xRatio)
                        put("yRatio", it.placement.yRatio)
                        put("widthRatio", it.placement.widthRatio)
                        put("heightRatio", it.placement.heightRatio)
                    })
                })
            }

            val artifactsArray = JSONArray()

            injectedArtifacts.forEach { artifact ->
                artifactsArray.put(JSONObject().apply {
                    put("type", artifact.type.name)
                    put("identifier", artifact.identifier)
                    put("digestSha256", artifact.digestSha256)
                })
            }

            put("injectedArtifacts", artifactsArray)

            if (includeFinalManifestSignature) {
                put(
                    "finalManifestSignatureBase64",
                    finalManifestSignatureBase64
                )
            }
        }.toString()
    }

    companion object {

        fun fromJsonString(raw: String): IntegrityManifestV4? {
            return try {
                val json = JSONObject(raw)

                val qrPlacement = if (json.has("qrPlacement")) {
                    val placement = json.getJSONObject("qrPlacement")

                    PlacementMetaV4(
                        pageIndex = placement.getInt("pageIndex"),
                        xRatio = placement.getDouble("xRatio"),
                        yRatio = placement.getDouble("yRatio"),
                        widthRatio = placement.getDouble("widthRatio"),
                        heightRatio = placement.getDouble("heightRatio")
                    )
                } else {
                    null
                }

                val signatureMeta = if (json.has("signatureMeta")) {
                    val signature = json.getJSONObject("signatureMeta")
                    val placement = signature.getJSONObject("placement")

                    SignatureMetaV4(
                        signatureDigestSha256 =
                            signature.getString("signatureDigestSha256"),

                        placement = PlacementMetaV4(
                            pageIndex = placement.getInt("pageIndex"),
                            xRatio = placement.getDouble("xRatio"),
                            yRatio = placement.getDouble("yRatio"),
                            widthRatio = placement.getDouble("widthRatio"),
                            heightRatio = placement.getDouble("heightRatio")
                        )
                    )
                } else {
                    null
                }

                val artifacts = mutableListOf<InjectedArtifactV4>()

                if (json.has("injectedArtifacts")) {
                    val array = json.getJSONArray("injectedArtifacts")

                    for (i in 0 until array.length()) {
                        val artifact = array.getJSONObject(i)

                        artifacts.add(
                            InjectedArtifactV4(
                                type = InjectedArtifactType.valueOf(
                                    artifact.getString("type")
                                ),
                                identifier = artifact.getString("identifier"),
                                digestSha256 = artifact.getString("digestSha256")
                            )
                        )
                    }
                }

                IntegrityManifestV4(
                    version = json.optString(
                        "version",
                        RECEIPT_VERSION_V4
                    ),
                    receiptId = json.getString("receiptId"),
                    createdAtMillis = json.getLong("createdAtMillis"),
                    documentFormat = DocumentFormatV4.valueOf(
                        json.getString("documentFormat")
                    ),
                    stampMode = StampModeV4.valueOf(
                        json.getString("stampMode")
                    ),
                    originalDocSha256 = json.getString("originalDocSha256"),
                    issuerId = json.getString("issuerId"),
                    originalPerceptualHash = json.optString(
                        "originalPerceptualHash",
                        ""
                    ),
                    qrPlacement = qrPlacement,
                    signatureMeta = signatureMeta,
                    injectedArtifacts = artifacts,
                    finalManifestSignatureBase64 = json.optString(
                        "finalManifestSignatureBase64",
                        ""
                    )
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

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
    val originalDocSha256: String,
    val manifestSha256: String,
    val qrPlacement: PlacementMetaV4? = null,
    val signatureMeta: SignatureMetaV4? = null,
    val digitalSignatureBase64: String = ""
) {

    fun toCanonicalSigningBytes(): ByteArray {
        val canonical = buildString {
            append(version).append('\n')
            append(receiptId).append('\n')
            append(issuerId).append('\n')
            append(timestampMillis).append('\n')
            append(nonce).append('\n')
            append(documentFormat.name).append('\n')
            append(stampMode.name).append('\n')
            append(originalDocSha256).append('\n')
            append(manifestSha256).append('\n')

            append(
                qrPlacement?.let {
                    "${it.pageIndex},${it.xRatio},${it.yRatio}," +
                        "${it.widthRatio},${it.heightRatio}"
                } ?: "none"
            ).append('\n')

            append(signatureMeta?.signatureDigestSha256 ?: "none")
        }

        return canonical.toByteArray(Charsets.UTF_8)
    }

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
            put("originalDocSha256", originalDocSha256)
            put("manifestSha256", manifestSha256)
            put("digitalSignatureBase64", digitalSignatureBase64)

            put(
                "integrityManifest",
                JSONObject(integrityManifest.toJsonString())
            )

            qrPlacement?.let {
                put("qrPlacement", JSONObject().apply {
                    put("pageIndex", it.pageIndex)
                    put("xRatio", it.xRatio)
                    put("yRatio", it.yRatio)
                    put("widthRatio", it.widthRatio)
                    put("heightRatio", it.heightRatio)
                })
            }

            signatureMeta?.let {
                put("signatureMeta", JSONObject().apply {
                    put("signatureDigestSha256", it.signatureDigestSha256)
                    put("placement", JSONObject().apply {
                        put("pageIndex", it.placement.pageIndex)
                        put("xRatio", it.placement.xRatio)
                        put("yRatio", it.placement.yRatio)
                        put("widthRatio", it.placement.widthRatio)
                        put("heightRatio", it.placement.heightRatio)
                    })
                })
            }
        }.toString()
    }

    companion object {

        fun fromQrJsonString(raw: String): ReceiptPayloadV4? {
            return try {
                val json = JSONObject(raw)

                val manifest = IntegrityManifestV4.fromJsonString(
                    json.getJSONObject("integrityManifest").toString()
                ) ?: return null

                val qrPlacement = if (json.has("qrPlacement")) {
                    val placement = json.getJSONObject("qrPlacement")

                    PlacementMetaV4(
                        pageIndex = placement.getInt("pageIndex"),
                        xRatio = placement.getDouble("xRatio"),
                        yRatio = placement.getDouble("yRatio"),
                        widthRatio = placement.getDouble("widthRatio"),
                        heightRatio = placement.getDouble("heightRatio")
                    )
                } else {
                    null
                }

                val signatureMeta = if (json.has("signatureMeta")) {
                    val signature = json.getJSONObject("signatureMeta")
                    val placement = signature.getJSONObject("placement")

                    SignatureMetaV4(
                        signatureDigestSha256 =
                            signature.getString("signatureDigestSha256"),

                        placement = PlacementMetaV4(
                            pageIndex = placement.getInt("pageIndex"),
                            xRatio = placement.getDouble("xRatio"),
                            yRatio = placement.getDouble("yRatio"),
                            widthRatio = placement.getDouble("widthRatio"),
                            heightRatio = placement.getDouble("heightRatio")
                        )
                    )
                } else {
                    null
                }

                ReceiptPayloadV4(
                    version = json.optString(
                        "version",
                        RECEIPT_VERSION_V4
                    ),
                    receiptId = json.getString("receiptId"),
                    issuerId = json.getString("issuerId"),
                    publicKeyBase64 = json.optString(
                        "publicKeyBase64",
                        ""
                    ),
                    timestampMillis = json.getLong("timestampMillis"),
                    nonce = json.optString("nonce", ""),
                    documentFormat = DocumentFormatV4.valueOf(
                        json.getString("documentFormat")
                    ),
                    stampMode = StampModeV4.valueOf(
                        json.getString("stampMode")
                    ),
                    integrityManifest = manifest,
                    originalDocSha256 = json.getString("originalDocSha256"),
                    manifestSha256 = json.getString("manifestSha256"),
                    qrPlacement = qrPlacement,
                    signatureMeta = signatureMeta,
                    digitalSignatureBase64 = json.optString(
                        "digitalSignatureBase64",
                        ""
                    )
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
