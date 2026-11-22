package com.ali.receiptshield.crypto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
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
        const val DOCX_MANIFEST_ENTRY = "word/rs_v4_manifest.json"
        const val DOCX_QR_ENTRY = "word/media/receiptshield_qr.png"
        private val IMAGE_FOOTER_MAGIC = "RSV4IMG1".toByteArray(Charsets.US_ASCII)
        private val PDF_MANIFEST_PREFIX = "%RS_V4_MANIFEST:".toByteArray(Charsets.US_ASCII)
        private val PDF_MANIFEST_SUFFIX = ":END_RS_V4_MANIFEST%".toByteArray(Charsets.US_ASCII)
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

    // ---------------------------------------------------------
    // MANIFEST EMBEDDING & EXTRACTION BY FORMAT
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

    fun embedManifestInDocxEntries(
        entries: LinkedHashMap<String, ByteArray>,
        manifest: IntegrityManifestV4
    ) {
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
            if (stampedImageBytes[footerStart + i] != IMAGE_FOOTER_MAGIC[i]) {
                return null
            }
        }

        val lenStart = footerStart - 4
        val manifestLength = ByteBuffer.wrap(stampedImageBytes, lenStart, 4).int
        if (manifestLength <= 0 || lenStart - manifestLength < 0) return null

        val manifestStart = lenStart - manifestLength
        val manifestBytes = stampedImageBytes.copyOfRange(manifestStart, lenStart)
        val cleanImageBytes = stampedImageBytes.copyOfRange(0, manifestStart)

        return try {
            // FIX #4: Unwrap nullable manifest with ?: return null
            // Otherwise the compiler sees Pair<ByteArray, IntegrityManifestV4?>
            // which doesn't match the declared return type Pair<ByteArray, IntegrityManifestV4>?
            val manifest = IntegrityManifestV4.fromJsonString(String(manifestBytes, Charsets.UTF_8))
                ?: return null
            Pair(cleanImageBytes, manifest)
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------
    // FORMAT-SPECIFIC INTEGRITY & BINDING VERIFICATION
    // ---------------------------------------------------------

    fun verifyPayloadAndManifestBinding(
        payload: ReceiptPayloadV4,
        manifest: IntegrityManifestV4,
        expectedFormat: DocumentFormatV4? = null
    ): Boolean {
        if (payload.version != RECEIPT_VERSION_V4 || manifest.version != RECEIPT_VERSION_V4) {
            return false
        }
        if (expectedFormat != null && (payload.documentFormat != expectedFormat || manifest.documentFormat != expectedFormat)) {
            return false
        }
        if (payload.receiptId != manifest.receiptId) return false
        if (payload.documentFormat != manifest.documentFormat) return false
        if (payload.issuerId != manifest.issuerId) return false
        if (!payload.originalDocSha256.equals(manifest.originalDocSha256, ignoreCase = true)) {
            return false
        }

        val actualManifestSha = computeManifestDigest(manifest)
        if (!payload.manifestSha256.equals(actualManifestSha, ignoreCase = true)) {
            return false
        }

        if (payload.qrPlacement != manifest.qrPlacement) return false
        if (payload.signatureMeta != manifest.signatureMeta) return false

        return true
    }

    fun verifyPdfIntegrity(
        pdfBytes: ByteArray,
        manifest: IntegrityManifestV4
    ): Boolean {
        return try {
            val doc = PDDocument.load(ByteArrayInputStream(pdfBytes))
            doc.use { loaded ->
                if (loaded.numberOfPages <= 0) return false
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
                        for (p in pixels) pixelBuffer.putInt(p)
                        digest.update(pixelBuffer.array())
                    } finally {
                        bitmap.recycle()
                    }
                }

                val renderedDigest = digest.digest().joinToString("") { "%02x".format(it) }
                val expectedArtifact = manifest.injectedArtifacts.firstOrNull {
                    it.type == InjectedArtifactType.METADATA_STREAM && it.identifier == "pdf_rendered_pages"
                }
                if (expectedArtifact != null) {
                    expectedArtifact.digestSha256.equals(renderedDigest, ignoreCase = true)
                } else {
                    manifest.originalDocSha256.isNotBlank() && renderedDigest.isNotBlank()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun computePdfRenderedPagesDigest(pdfBytes: ByteArray): String {
        val doc = PDDocument.load(ByteArrayInputStream(pdfBytes))
        doc.use { loaded ->
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
                    for (p in pixels) pixelBuffer.putInt(p)
                    digest.update(pixelBuffer.array())
                } finally {
                    bitmap.recycle()
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun verifyDocxIntegrity(
        docxBytes: ByteArray,
        manifest: IntegrityManifestV4
    ): Boolean {
        return try {
            val entries = unzipEntries(docxBytes)
            if (!entries.containsKey("word/document.xml")) return false

            val injectedNames = mutableSetOf(DOCX_MANIFEST_ENTRY)
            for (artifact in manifest.injectedArtifacts) {
                when (artifact.type) {
                    InjectedArtifactType.QR_IMAGE,
                    InjectedArtifactType.SIGNATURE_IMAGE,
                    InjectedArtifactType.MANIFEST -> {
                        val partBytes = entries[artifact.identifier] ?: return false
                        if (!sha256(partBytes).equals(artifact.digestSha256, ignoreCase = true)) {
                            return false
                        }
                        injectedNames.add(artifact.identifier)
                    }
                    InjectedArtifactType.RELATIONSHIP,
                    InjectedArtifactType.CONTENT_TYPE,
                    InjectedArtifactType.METADATA_STREAM -> {
                        injectedNames.add(artifact.identifier)
                    }
                    // FIX #5: Handle remaining enum values so the when is exhaustive
                    // (QR, SIGNATURE, UNKNOWN and any future types)
                    else -> {
                        injectedNames.add(artifact.identifier)
                    }
                }
            }

            val reconstructed = LinkedHashMap<String, ByteArray>()
            for ((name, bytes) in entries) {
                if (name in injectedNames || name == DOCX_MANIFEST_ENTRY) continue

                when {
                    name.endsWith(".rels") -> {
                        reconstructed[name] = cleanRelationshipsXml(bytes, manifest.injectedArtifacts)
                    }
                    name == "[Content_Types].xml" -> {
                        reconstructed[name] = cleanContentTypesXml(bytes, manifest.injectedArtifacts)
                    }
                    name == "word/document.xml" -> {
                        reconstructed[name] = cleanDocumentXmlReceiptShieldNodes(bytes)
                    }
                    else -> {
                        reconstructed[name] = bytes
                    }
                }
            }

            val strippedDocxBytes = zipEntriesToBytes(reconstructed)
            val strippedDigest = sha256(strippedDocxBytes)
            strippedDigest.equals(manifest.originalDocSha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    fun verifyImageIntegrity(
        cleanImageBytes: ByteArray,
        manifest: IntegrityManifestV4
    ): Boolean {
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(cleanImageBytes, 0, cleanImageBytes.size)
            ?: return false
        return try {
            if (bitmap.width <= 0 || bitmap.height <= 0 || manifest.originalDocSha256.isBlank()) {
                return false
            }

            val expectedStampedArtifact = manifest.injectedArtifacts.firstOrNull {
                it.type == InjectedArtifactType.METADATA_STREAM &&
                    (it.identifier == "image_stamped_png" ||
                        it.identifier == "image_qr_stamped_png" ||
                        it.identifier == "image_signature_stamped_png")
            }

            if (expectedStampedArtifact != null) {
                val actualDigest = sha256(cleanImageBytes)
                actualDigest.equals(expectedStampedArtifact.digestSha256, ignoreCase = true)
            } else {
                true
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ---------------------------------------------------------
    // INTERNAL CANONICALIZATION HELPERS
    // ---------------------------------------------------------

    private fun cleanRelationshipsXml(
        xmlBytes: ByteArray,
        artifacts: List<InjectedArtifactV4>
    ): ByteArray {
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
                val target = node.getAttribute("Target")
                if (target in targetPaths) {
                    toRemove.add(node)
                }
            }
        }
        for (node in toRemove) {
            root.removeChild(node)
        }
        return serializeXml(doc)
    }

    private fun cleanContentTypesXml(
        xmlBytes: ByteArray,
        artifacts: List<InjectedArtifactV4>
    ): ByteArray {
        val injectedPartNames = artifacts
            .filter {
                it.type == InjectedArtifactType.QR_IMAGE ||
                    it.type == InjectedArtifactType.SIGNATURE_IMAGE ||
                    it.type == InjectedArtifactType.MANIFEST
            }
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
                val partName = node.getAttribute("PartName")
                if (partName in injectedPartNames) {
                    toRemove.add(node)
                }
            }
        }
        for (node in toRemove) {
            root.removeChild(node)
        }
        return serializeXml(doc)
    }

    private fun cleanDocumentXmlReceiptShieldNodes(xmlBytes: ByteArray): ByteArray {
        val doc = parseXml(xmlBytes)
        val allElements = doc.getElementsByTagName("*")
        val paragraphsToRemove = mutableListOf<Node>()

        for (i in 0 until allElements.length) {
            val el = allElements.item(i) as? Element ?: continue
            if (el.tagName.endsWith("docPr")) {
                val nameAttr = el.getAttribute("name")
                if (nameAttr.startsWith("ReceiptShield", ignoreCase = true)) {
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

        for (p in paragraphsToRemove) {
            p.parentNode?.removeChild(p)
        }
        return serializeXml(doc)
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
            }
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
