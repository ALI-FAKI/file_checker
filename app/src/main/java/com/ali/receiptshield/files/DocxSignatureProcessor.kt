
package com.ali.receiptshield.files

import android.graphics.BitmapFactory
import com.ali.receiptshield.signature.DocumentSignature
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DocxSignatureProcessor {

    private const val EMU_PER_TWIP = 635L

    /**
     * Adds a signature image to an existing DOCX document.
     *
     * The signature is inserted as an inline image near the
     * end of the document, before section properties.
     *
     * This method does not replace existing document content.
     */
    @Throws(IOException::class)
    fun stampDocxWithSignature(
        originalBytes: ByteArray,
        signature: DocumentSignature,
        outputFile: File
    ) {
        if (!signature.isSignatureFileAvailable()) {
            throw IOException("Signature image is missing or empty.")
        }

        val signatureFile = signature.signatureFile

        val imageOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(
            signatureFile.absolutePath,
            imageOptions
        )

        val imageWidth = imageOptions.outWidth
        val imageHeight = imageOptions.outHeight

        if (imageWidth <= 0 || imageHeight <= 0) {
            throw IOException("Invalid signature image.")
        }

        val placement = signature.placement.normalized()

        val entries = unzip(originalBytes)

        val documentXml = entries["word/document.xml"]
            ?: throw IOException(
                "Invalid DOCX: word/document.xml was not found."
            )

        val documentText = documentXml.toString(Charsets.UTF_8)

        if (!documentText.contains("</w:body>")) {
            throw IOException(
                "Invalid DOCX: document body was not found."
            )
        }

        // -----------------------------------------------------
        // IMAGE FILE
        // -----------------------------------------------------

        val imageName = createUniqueImageName(entries)

        val imagePath = "word/media/$imageName"

        entries[imagePath] = signatureFile.readBytes()

        // -----------------------------------------------------
        // DOCUMENT RELATIONSHIPS
        // -----------------------------------------------------

        val relationshipPath = "word/_rels/document.xml.rels"

        val relationshipsXml = entries[relationshipPath]
            ?.toString(Charsets.UTF_8)
            ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""

        val relationshipId = nextRelationshipId(relationshipsXml)

        val relationship =
            """<Relationship Id="$relationshipId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/$imageName"/>"""

        val updatedRelationships = relationshipsXml.replace(
            "</Relationships>",
            "$relationship</Relationships>"
        )

        entries[relationshipPath] =
            updatedRelationships.toByteArray(Charsets.UTF_8)

        // -----------------------------------------------------
        // CONTENT TYPES
        // -----------------------------------------------------

        val contentTypesPath = "[Content_Types].xml"

        val contentTypesXml = entries[contentTypesPath]
            ?.toString(Charsets.UTF_8)
            ?: throw IOException(
                "DOCX content types information was not found."
            )

        if (!Regex("""Extension="png"""", RegexOption.IGNORE_CASE)
                .containsMatchIn(contentTypesXml)
        ) {
            val pngDefault =
                """<Default Extension="png" ContentType="image/png"/>"""

            entries[contentTypesPath] = contentTypesXml.replace(
                "</Types>",
                "$pngDefault</Types>"
            ).toByteArray(Charsets.UTF_8)
        }

        // -----------------------------------------------------
        // IMAGE DIMENSIONS
        // -----------------------------------------------------

        val pageWidthTwips = getPageWidthTwips(documentText)

        val signatureWidthEmu = (
            pageWidthTwips *
                EMU_PER_TWIP *
                placement.width
            ).toLong()

        val aspectRatio = imageHeight.toDouble() / imageWidth.toDouble()

        val signatureHeightEmu = (
            signatureWidthEmu * aspectRatio
            ).toLong()

        // -----------------------------------------------------
        // SIGNATURE DRAWING
        // -----------------------------------------------------

        val drawingParagraph = createSignatureDrawingParagraph(
            relationshipId = relationshipId,
            imageName = imageName,
            widthEmu = signatureWidthEmu,
            heightEmu = signatureHeightEmu
        )

        // Insert before section properties when present.
        // Otherwise insert immediately before the body closes.
        val sectionPropertiesRegex = Regex(
            """<w:sectPr(?:\s|>)""",
            RegexOption.IGNORE_CASE
        )

        val sectionMatch = sectionPropertiesRegex.find(documentText)

        val updatedDocument = if (sectionMatch != null) {
            documentText.substring(0, sectionMatch.range.first) +
                drawingParagraph +
                documentText.substring(sectionMatch.range.first)
        } else {
            documentText.replace(
                "</w:body>",
                "$drawingParagraph</w:body>"
            )
        }

        entries["word/document.xml"] =
            updatedDocument.toByteArray(Charsets.UTF_8)

        // -----------------------------------------------------
        // WRITE OUTPUT DOCX
        // -----------------------------------------------------

        outputFile.parentFile?.mkdirs()

        ZipOutputStream(outputFile.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * Creates the WordprocessingML paragraph containing
     * the signature image.
     */
    private fun createSignatureDrawingParagraph(
        relationshipId: String,
        imageName: String,
        widthEmu: Long,
        heightEmu: Long
    ): String {

        return """
<w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
     xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
     xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
     xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
     xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
    <w:r>
        <w:drawing>
            <wp:inline distT="0" distB="0" distL="0" distR="0">
                <wp:extent cx="$widthEmu" cy="$heightEmu"/>
                <wp:docPr id="9002" name="ReceiptShield Signature"/>
                <wp:cNvGraphicFramePr/>
                <a:graphic>
                    <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                        <pic:pic>
                            <pic:nvPicPr>
                                <pic:cNvPr id="0" name="$imageName"/>
                                <pic:cNvPicPr/>
                            </pic:nvPicPr>
                            <pic:blipFill>
                                <a:blip r:embed="$relationshipId"/>
                                <a:stretch>
                                    <a:fillRect/>
                                </a:stretch>
                            </pic:blipFill>
                            <pic:spPr>
                                <a:xfrm>
                                    <a:off x="0" y="0"/>
                                    <a:ext cx="$widthEmu" cy="$heightEmu"/>
                                </a:xfrm>
                                <a:prstGeom prst="rect">
                                    <a:avLst/>
                                </a:prstGeom>
                            </pic:spPr>
                        </pic:pic>
                    </a:graphicData>
                </a:graphic>
            </wp:inline>
        </w:drawing>
    </w:r>
</w:p>
""".trimIndent()
    }

    /**
     * Reads the document page width from section properties.
     * Uses A4 width as the fallback.
     */
    private fun getPageWidthTwips(xml: String): Long {
        val sectionRegex = Regex(
            """<w:pgSz\b[^>]*>""",
            RegexOption.IGNORE_CASE
        )

        val sectionTag = sectionRegex.find(xml)?.value
            ?: return 11906L

        val widthRegex = Regex(
            """w:w\s*=\s*["'](\d+)["']"""
        )

        return widthRegex.find(sectionTag)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: 11906L
    }

    private fun createUniqueImageName(
        entries: Map<String, ByteArray>
    ): String {
        var index = 1

        while (entries.containsKey(
                "word/media/receiptshield_signature_$index.png"
            )
        ) {
            index++
        }

        return "receiptshield_signature_$index.png"
    }

    private fun nextRelationshipId(xml: String): String {
        val regex = Regex("""Id="rId(\d+)"""")

        val highest = regex.findAll(xml)
            .mapNotNull {
                it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 0

        return "rId${highest + 1}"
    }

    private fun unzip(
        bytes: ByteArray
    ): LinkedHashMap<String, ByteArray> {

        val entries = LinkedHashMap<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)

                    entries[entry.name] = output.toByteArray()
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return entries
    }
}
