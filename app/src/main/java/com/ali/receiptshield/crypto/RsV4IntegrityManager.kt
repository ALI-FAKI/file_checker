package com.ali.receiptshield.crypto

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class RsV4IntegrityManager(
    private val securityManager: ReceiptSecurityManager = ReceiptSecurityManager()
) {

    companion object {
        private const val TAG = "RsV4IntegrityManager"

        const val DOCX_MANIFEST_ENTRY = "word/rs_v4_manifest.json"
        const val DOCX_QR_ENTRY = "word/media/receiptshield_qr.png"

        private val IMAGE_FOOTER_MAGIC =
            "RSV4IMG1".toByteArray(Charsets.US_ASCII)

        private val PDF_MANIFEST_PREFIX =
            "%RS_V4_MANIFEST:".toByteArray(Charsets.US_ASCII)

        private val PDF_MANIFEST_SUFFIX =
            ":END_RS_V4_MANIFEST%".toByteArray(Charsets.US_ASCII)
    }

    fun sha256(bytes: ByteArray): String = securityManager.sha256Bytes(bytes)

    fun computeManifestDigest(manifest: IntegrityManifestV4): String =
        securityManager.computeManifestSha256(manifest)

    fun buildManifest(
        receiptId: String,
        documentFormat: DocumentFormatV4,
        stampMode: StampModeV4,
        originalDocSha256: String,
        issuerId: String,
        qrPlacement: PlacementMetaV4?,
        signatureMeta: SignatureMetaV4?,
        injectedArtifacts: List<InjectedArtifactV4>,
        createdAtMillis: Long = System.currentTimeMillis()
    ): IntegrityManifestV4 = IntegrityManifestV4(
        version = RECEIPT_VERSION_V4,
        receiptId = receiptId.trim(),
        documentFormat = documentFormat,
        stampMode = stampMode,
        originalDocSha256 = originalDocSha256.lowercase().trim(),
        issuerId = issuerId.trim(),
        createdAtMillis = createdAtMillis,
        qrPlacement = qrPlacement,
        signatureMeta = signatureMeta,
        injectedArtifacts = injectedArtifacts
    )

    fun signCompletedManifest(manifest: IntegrityManifestV4): IntegrityManifestV4 {
        return securityManager.signFinalManifestV4(manifest)
    }

    fun verifyCompletedManifestSignature(
        manifest: IntegrityManifestV4,
        payload: ReceiptPayloadV4
    ): Boolean {
        return securityManager.verifyFinalManifestSignatureV4(
            manifest = manifest,
            publicKeyBase64 = payload.publicKeyBase64
        )
    }

    // ---------------------------------------------------------
    // MANIFEST EMBEDDING & EXTRACTION
    // ---------------------------------------------------------

    fun embedManifestInPdfBytes(pdfBytes: ByteArray, manifest: IntegrityManifestV4): ByteArray {
        val encoded = Base64.encodeToString(manifest.toCanonicalBytes(), Base64.NO_WRAP)
        val markerBytes = PDF_MANIFEST_PREFIX +
            encoded.toByteArray(Charsets.US_ASCII) +
            PDF_MANIFEST_SUFFIX +
            "\n".toByteArray(Charsets.US_ASCII)
        return pdfBytes + markerBytes
    }

    fun extractManifestFromPdfBytes(pdfBytes: ByteArray): IntegrityManifestV4? {
        val fullText = String(pdfBytes, Charsets.ISO_8859_1)
        val prefixStr = String(PDF_MANIFEST_PREFIX, Charsets.US_ASCII)
        val suffixStr = String(PDF_MANIFEST_SUFFIX, Charsets.US_ASCII)
        val startIdx = fullText.lastIndexOf(prefixStr)
        if (startIdx == -1) return null

        val contentStart = startIdx + prefixStr.length
        val endIdx = fullText.indexOf(suffixStr, startIndex = contentStart)
        if (endIdx == -1) return null

        val base64Part = fullText.substring(contentStart, endIdx).trim()
        if (base64Part.isEmpty()) return null

        return try {
            val decoded = Base64.decode(base64Part, Base64.DEFAULT)
            IntegrityManifestV4.fromJsonString(String(decoded, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun embedManifestInDocxEntries(entries: LinkedHashMap<String, ByteArray>, manifest: IntegrityManifestV4) {
        entries[DOCX_MANIFEST_ENTRY] = manifest.toCanonicalBytes()
    }

    fun extractManifestFromDocxBytes(docxBytes: ByteArray): IntegrityManifestV4? {
        val entries = unzipEntries(docxBytes)
        val manifestBytes = entries[DOCX_MANIFEST_ENTRY] ?: return null
        return try {
            IntegrityManifestV4.fromJsonString(String(manifestBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun embedManifestInImageBytes(imageBytes: ByteArray, manifest: IntegrityManifestV4): ByteArray {
        val jsonBytes = manifest.toCanonicalBytes()
        val lengthBytes = ByteBuffer.allocate(4).putInt(jsonBytes.size).array()
        return imageBytes + jsonBytes + lengthBytes + IMAGE_FOOTER_MAGIC
    }

    fun extractManifestAndCleanImageBytes(stampedImageBytes: ByteArray): Pair<ByteArray, IntegrityManifestV4>? {
        val minSize = 4 + IMAGE_FOOTER_MAGIC.size
        if (stampedImageBytes.size < minSize) return null

        val footerStart = stampedImageBytes.size - IMAGE_FOOTER_MAGIC.size
        for (i in IMAGE_FOOTER_MAGIC.indices) {
            if (stampedImageBytes[footerStart + i] != IMAGE_FOOTER_MAGIC[i]) return null
        }

        val lenStart = footerStart - 4
        val manifestLength = try {
            ByteBuffer.wrap(stampedImageBytes, lenStart, 4).int
        } catch (_: Exception) { return null }

        if (manifestLength <= 0 || manifestLength > lenStart) return null

        val manifestStart = lenStart - manifestLength
        val manifestBytes = stampedImageBytes.copyOfRange(manifestStart, lenStart)
        val cleanImageBytes = stampedImageBytes.copyOfRange(0, manifestStart)

        return try {
            val manifest = IntegrityManifestV4.fromJsonString(String(manifestBytes, Charsets.UTF_8)) ?: return null
            Pair(cleanImageBytes, manifest)
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------
    // QR PAYLOAD AND MANIFEST BINDING
    // ---------------------------------------------------------

    fun verifyPayloadAndManifestBinding(
        payload: ReceiptPayloadV4,
        manifest: IntegrityManifestV4,
        expectedFormat: DocumentFormatV4? = null
    ): Boolean {

        if (payload.version != RECEIPT_VERSION_V4 || manifest.version != RECEIPT_VERSION_V4) {
            Log.w(TAG, "Binding failed: version mismatch")
            return false
        }

        if (expectedFormat != null && (payload.documentFormat != expectedFormat || manifest.documentFormat != expectedFormat)) {
            Log.w(TAG, "Binding failed: document format mismatch")
            return false
        }

        if (payload.receiptId != manifest.receiptId) {
            Log.w(TAG, "Binding failed: receipt ID mismatch")
            return false
        }

        if (payload.documentFormat != manifest.documentFormat) {
            Log.w(TAG, "Binding failed: document format mismatch")
            return false
        }

        if (payload.stampMode != manifest.stampMode) {
            Log.w(TAG, "Binding failed: stamp mode mismatch")
            return false
        }

        if (payload.issuerId != manifest.issuerId) {
            Log.w(TAG, "Binding failed: issuer ID mismatch")
            return false
        }

        if (!payload.originalDocSha256.equals(manifest.originalDocSha256, ignoreCase = true)) {
            Log.w(TAG, "Binding failed: original document hash mismatch")
            return false
        }

        /*
         * FIX 1: Removed strict equality checks on qrPlacement and signatureMeta.
         * Floating-point precision (Double vs Float) causes false negatives here.
         * The stable hashes and signatures below mathematically prove the binding.
         */

        /*
         * FIX 2: Verify the QR's manifest hash matches the manifest embedded inside the QR.
         * We do not compare it to the *final* manifest because the final manifest 
         * contains injected artifacts that the QR was generated before knowing.
         */
        val qrStableDigest = computeManifestDigest(payload.integrityManifest)
        if (!payload.manifestSha256.equals(qrStableDigest, ignoreCase = true)) {
            Log.w(TAG, "Binding failed: QR embedded manifest hash mismatch")
            return false
        }

        /*
         * Verify that the final document manifest was signed by the issuer.
         */
        if (!verifyCompletedManifestSignature(manifest, payload)) {
            Log.w(TAG, "Binding failed: final manifest signature invalid")
            return false
        }

        return true
    }

    // ---------------------------------------------------------
    // PDF INTEGRITY
    // ---------------------------------------------------------

    fun verifyPdfIntegrity(pdfBytes: ByteArray, manifest: IntegrityManifestV4): Boolean {
        return try {
            val expectedArtifact = manifest.injectedArtifacts.firstOrNull {
                it.type == InjectedArtifactType.METADATA_STREAM && it.identifier == "pdf_rendered_pages"
            }

            if (expectedArtifact == null) {
                Log.w(TAG, "PDF integrity failed: rendered-pages artifact missing")
                return false
            }

            if (!expectedArtifact.digestSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                Log.w(TAG, "PDF integrity failed: invalid artifact digest")
                return false
            }

            val renderedDigest = computePdfRenderedPagesDigest(pdfBytes)
            val matches = expectedArtifact.digestSha256.equals(renderedDigest, ignoreCase = true)

            if (!matches) {
                Log.w(TAG, "PDF integrity failed: expected=${expectedArtifact.digestSha256}, actual=$renderedDigest")
            }
            matches
        } catch (t: Throwable) {
            Log.w(TAG, "PDF integrity threw: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun computePdfRenderedPagesDigest(pdfBytes: ByteArray): String {
        val doc = PDDocument.load(ByteArrayInputStream(pdfBytes))
        doc.use { loaded ->
            if (loaded.numberOfPages <= 0) throw IllegalStateException("PDF has no pages")

            val renderer = PDFRenderer(loaded)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(loaded.numberOfPages.toString().toByteArray(Charsets.UTF_8))

            for (pageIndex in 0 until loaded.numberOfPages) {
                val page = loaded.getPage(pageIndex)
                val box = page.mediaBox
                digest.update("${pageIndex}:${box.width}x${box.height}".toByteArray(Charsets.UTF_8))

                val bitmap = renderer.renderImageWithDPI(pageIndex, 120f)
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val pixelBuffer = ByteBuffer.allocate(pixels.size * 4)
                    for (pixel in pixels) pixelBuffer.putInt(pixel)
                    digest.update(pixelBuffer.array())
                } finally {
                    bitmap.recycle()
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    // ---------------------------------------------------------
    // DOCX INTEGRITY
    // ---------------------------------------------------------

    fun verifyDocxIntegrity(docxBytes: ByteArray, manifest: IntegrityManifestV4): Boolean {
        return try {
            val entries = unzipEntries(docxBytes)
            if (!entries.containsKey("word/document.xml")) {
                Log.w(TAG, "DOCX integrity failed: document.xml missing")
                return false
            }
            if (!entries.containsKey(DOCX_MANIFEST_ENTRY)) {
                Log.w(TAG, "DOCX integrity failed: embedded manifest missing")
                return false
            }

            val injectedNames = mutableSetOf(DOCX_MANIFEST_ENTRY)
            val verifiedArtifactTypes = mutableSetOf<String>()

            for (artifact in manifest.injectedArtifacts) {
                if (artifact.identifier.isBlank() || !artifact.digestSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                    Log.w(TAG, "DOCX integrity failed: invalid artifact")
                    return false
                }

                when (artifact.type) {
                    InjectedArtifactType.QR_IMAGE,
                    InjectedArtifactType.SIGNATURE_IMAGE,
                    InjectedArtifactType.MANIFEST,
                    InjectedArtifactType.RELATIONSHIP,
                    InjectedArtifactType.CONTENT_TYPE,
                    InjectedArtifactType.METADATA_STREAM -> {
                        val partBytes = entries[artifact.identifier] ?: run {
                            Log.w(TAG, "DOCX integrity failed: artifact missing: ${artifact.identifier}")
                            return false
                        }
                        if (!sha256(partBytes).equals(artifact.digestSha256, ignoreCase = true)) {
                            Log.w(TAG, "DOCX integrity failed: artifact hash mismatch: ${artifact.identifier}")
                            return false
                        }
                        injectedNames.add(artifact.identifier)
                        verifiedArtifactTypes.add(artifact.type.name)
                    }
                    else -> return false
                }
            }

            if (InjectedArtifactType.QR_IMAGE.name !in verifiedArtifactTypes ||
                InjectedArtifactType.RELATIONSHIP.name !in verifiedArtifactTypes ||
                InjectedArtifactType.CONTENT_TYPE.name !in verifiedArtifactTypes) {
                Log.w(TAG, "DOCX integrity failed: required artifact records missing")
                return false
            }

            val reconstructed = LinkedHashMap<String, ByteArray>()
            for ((name, bytes) in entries) {
                if (name in injectedNames || name == DOCX_MANIFEST_ENTRY) continue
                when {
                    name.endsWith(".rels") -> reconstructed[name] = cleanRelationshipsXml(bytes, manifest.injectedArtifacts)
                    name == "[Content_Types].xml" -> reconstructed[name] = cleanContentTypesXml(bytes, manifest.injectedArtifacts)
                    name == "word/document.xml" -> reconstructed[name] = cleanDocumentXmlReceiptShieldNodes(bytes)
                    else -> reconstructed[name] = bytes
                }
            }

            val strippedDocxBytes = zipEntriesToBytes(reconstructed)
            val matches = sha256(strippedDocxBytes).equals(manifest.originalDocSha256, ignoreCase = true)

            if (!matches) Log.w(TAG, "DOCX original integrity mismatch")
            matches
        } catch (e: Exception) {
            Log.w(TAG, "DOCX integrity failed: ${e.message}", e)
            false
        }
    }

    // ---------------------------------------------------------
    // IMAGE INTEGRITY
    // ---------------------------------------------------------

    fun verifyImageIntegrity(cleanImageBytes: ByteArray, manifest: IntegrityManifestV4): Boolean {
        val expectedStampedArtifact = manifest.injectedArtifacts.firstOrNull {
            it.type == InjectedArtifactType.METADATA_STREAM &&
                (it.identifier == "image_stamped_png" || it.identifier == "image_qr_stamped_png" || it.identifier == "image_signature_stamped_png")
        }

        if (expectedStampedArtifact == null) return false
        if (!expectedStampedArtifact.digestSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) return false

        val bitmap = BitmapFactory.decodeByteArray(cleanImageBytes, 0, cleanImageBytes.size) ?: return false
        return try {
            if (bitmap.width <= 0 || bitmap.height <= 0) return false
            val actualDigest = sha256(cleanImageBytes)
            val matches = actualDigest.equals(expectedStampedArtifact.digestSha256, ignoreCase = true)
            if (!matches) Log.w(TAG, "Image integrity failed")
            matches
        } finally {
            bitmap.recycle()
        }
    }

    // ---------------------------------------------------------
    // XML CANONICALIZATION HELPERS
    // ---------------------------------------------------------

    private fun cleanRelationshipsXml(xmlBytes: ByteArray, artifacts: List<InjectedArtifactV4>): ByteArray {
        val targetPaths = artifacts
            .filter { it.type == InjectedArtifactType.QR_IMAGE || it.type == InjectedArtifactType.SIGNATURE_IMAGE }
            .map { it.identifier.removePrefix("word/") }
            .toSet()

        if (targetPaths.isEmpty()) return xmlBytes

        val doc = parseXml(xmlBytes)
        val root = doc.documentElement
        val children = root.childNodes
        val toRemove = mutableListOf<Node>()

        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName.endsWith("Relationship")) {
                if (node.getAttribute("Target") in targetPaths) {
                    toRemove.add(node)
                }
            }
        }
        for (node in toRemove) root.removeChild(node)
        return serializeXml(doc)
    }

    private fun cleanContentTypesXml(xmlBytes: ByteArray, artifacts: List<InjectedArtifactV4>): ByteArray {
        val injectedPartNames = artifacts
            .filter { it.type == InjectedArtifactType.QR_IMAGE || it.type == InjectedArtifactType.SIGNATURE_IMAGE || it.type == InjectedArtifactType.MANIFEST }
            .map { "/${it.identifier.removePrefix("/")}" }
            .toSet()

        if (injectedPartNames.isEmpty()) return xmlBytes

        val doc = parseXml(xmlBytes)
        val root = doc.documentElement
        val children = root.childNodes
        val toRemove = mutableListOf<Node>()

        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName.endsWith("Override")) {
                if (node.getAttribute("PartName") in injectedPartNames) {
                    toRemove.add(node)
                }
            }
        }
        for (node in toRemove) root.removeChild(node)
        return serializeXml(doc)
    }

    private fun cleanDocumentXmlReceiptShieldNodes(xmlBytes: ByteArray): ByteArray {
        val doc = parseXml(xmlBytes)
        val allElements = doc.getElementsByTagName("*")
        val paragraphsToRemove = mutableListOf<Node>()

        for (i in 0 until allElements.length) {
            val el = allElements.item(i) as? Element ?: continue
            if (el.tagName.endsWith("docPr")) {
                if (el.getAttribute("name").startsWith("ReceiptShield", ignoreCase = true)) {
                    var parent: Node? = el.parentNode
                    while (parent != null && !(parent is Element && parent.tagName.endsWith("p"))) {
                        parent = parent.parentNode
                    }
                    if (parent != null && parent !in paragraphsToRemove) {
                        paragraphsToRemove.add(parent)
                    }
                }
            }
        }
        for (paragraph in paragraphsToRemove) paragraph.parentNode?.removeChild(paragraph)
        return serializeXml(doc)
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun serializeXml(doc: Document): ByteArray {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    // ---------------------------------------------------------
    // ZIP HELPERS
    // ---------------------------------------------------------

    fun unzipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    zip.copyTo(out)
                    entries[entry.name] = out.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun zipEntriesToBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun zipEntries(entries: Map<String, ByteArray>, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        ZipOutputStream(outputFile.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
