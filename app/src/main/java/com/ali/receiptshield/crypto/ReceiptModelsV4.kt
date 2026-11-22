package com.ali.receiptshield.crypto

import org.json.JSONArray
import org.json.JSONObject

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
    
    // FIX: Proper JSON serialization so no data is lost
    fun serialize(): String {
        val json = JSONObject()
        json.put("version", version)
        json.put("issuerId", issuerId)
        json.put("timestamp", timestamp)
        json.put("nonce", nonce)
        json.put("format", format.name)
        json.put("stampMode", stampMode.name)

        // Serialize Manifest
        val manifestJson = JSONObject().apply {
            put("originalFileHash", manifest.originalFileHash)
            put("originalPerceptualHash", manifest.originalPerceptualHash)
            
            val artifactsArray = JSONArray()
            for (artifact in manifest.injectedArtifacts) {
                val artifactJson = JSONObject().apply {
                    put("type", artifact.type.name)
                    put("pathInsideDocument", artifact.pathInsideDocument)
                    put("hash", artifact.hash)
                }
                artifactsArray.put(artifactJson)
            }
            put("injectedArtifacts", artifactsArray)
        }
        json.put("manifest", manifestJson)

        // Serialize Placement Meta (Optional)
        placementMeta?.let {
            json.put("placementMeta", JSONObject().apply {
                put("page", it.page)
                put("x", it.x.toDouble())
                put("y", it.y.toDouble())
                put("width", it.width.toDouble())
                put("height", it.height.toDouble())
            })
        }

        // Serialize Signature Meta (Optional)
        signatureMeta?.let {
            json.put("signatureMeta", JSONObject().apply {
                put("algorithm", it.algorithm)
                put("keyId", it.keyId)
                put("signatureBase64", it.signatureBase64)
            })
        }

        return json.toString()
    }

    companion object {
        // FIX: Added missing deserialize function
        fun deserialize(raw: String): ReceiptPayloadV4? {
            return try {
                val json = JSONObject(raw)
                
                // Parse Manifest
                val manifestJson = json.getJSONObject("manifest")
                val artifactsArray = manifestJson.getJSONArray("injectedArtifacts")
                val artifactsList = mutableListOf<InjectedArtifactV4>()
                
                for (i in 0 until artifactsArray.length()) {
                    val a = artifactsArray.getJSONObject(i)
                    artifactsList.add(
                        InjectedArtifactV4(
                            type = InjectedArtifactType.valueOf(a.getString("type")),
                            pathInsideDocument = a.getString("pathInsideDocument"),
                            hash = a.getString("hash")
                        )
                    )
                }

                val manifest = IntegrityManifestV4(
                    originalFileHash = manifestJson.getString("originalFileHash"),
                    originalPerceptualHash = manifestJson.getString("originalPerceptualHash"),
                    injectedArtifacts = artifactsList
                )

                // Parse Placement Meta (Optional)
                val placementMeta = if (json.has("placementMeta")) {
                    val p = json.getJSONObject("placementMeta")
                    PlacementMetaV4(
                        page = p.getInt("page"),
                        x = p.getDouble("x").toFloat(),
                        y = p.getDouble("y").toFloat(),
                        width = p.getDouble("width").toFloat(),
                        height = p.getDouble("height").toFloat()
                    )
                } else null

                // Parse Signature Meta (Optional)
                val signatureMeta = if (json.has("signatureMeta")) {
                    val s = json.getJSONObject("signatureMeta")
                    SignatureMetaV4(
                        algorithm = s.getString("algorithm"),
                        keyId = s.getString("keyId"),
                        signatureBase64 = s.getString("signatureBase64")
                    )
                } else null

                ReceiptPayloadV4(
                    version = json.getString("version"),
                    issuerId = json.getString("issuerId"),
                    timestamp = json.getLong("timestamp"),
                    nonce = json.getString("nonce"),
                    format = DocumentFormatV4.valueOf(json.getString("format")),
                    stampMode = StampModeV4.valueOf(json.getString("stampMode")),
                    manifest = manifest,
                    placementMeta = placementMeta,
                    signatureMeta = signatureMeta
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
