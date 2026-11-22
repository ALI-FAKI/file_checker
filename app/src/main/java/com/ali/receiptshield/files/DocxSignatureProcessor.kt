
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
    private const val DEFAULT_PAGE_WIDTH_TWIPS = 11906L
    private const val DEFAULT_PAGE_HEIGHT_TWIPS = 16838L

    /**
     * Adds a floating signature image to an existing DOCX document.
     *
     * The signature uses normalized page-relative X/Y coordinates
     * and a normalized width.
     *
     * The signature image keeps its original aspect ratio.
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
        // PAGE DIMENSIONS
        // -----------------------------------------------------

        val pageSize = getPageSizeTwips(documentText)

        val pageWidthEmu =
            pageSize.first * EMU_PER_TWIP

        val pageHeightEmu =
            pageSize.second * EMU_PER_TWIP

        // -----------------------------------------------------
        // SIGNATURE POSITION AND SIZE
        // -----------------------------------------------------

        val safeWidth = placement.width.coerceIn(0.08f, 0.80f)

        val xPosition = (
            pageWidthEmu * placement.x
        ).toLong().coerceIn(
            0L,
            pageWidthEmu - 1L
        )

        val yPosition = (
            pageHeightEmu * placement.y
        ).toLong().coerceIn(
            0L,
            pageHeightEmu - 1L
        )

        val availableWidth = pageWidthEmu - xPosition
        val availableHeight = pageHeightEmu - yPosition

        var signatureWidthEmu = (
            pageWidthEmu * safeWidth
        ).toLong()

        val aspectRatio =
            imageHeight.toDouble() / imageWidth.toDouble()

        var signatureHeightEmu = (
            signatureWidthEmu * aspectRatio
        ).toLong()

        // Keep the complete signature inside the page.
        if (signatureWidthEmu > availableWidth) {
            signatureWidthEmu = availableWidth
            signatureHeightEmu = (
                signatureWidthEmu * aspectRatio
            ).toLong()
        }

        if (signatureHeightEmu > availableHeight) {
            signatureHeightEmu = availableHeight
            signatureWidthEmu = (
                signatureHeightEmu / aspectRatio
            ).toLong()
        }

        if (signatureWidthEmu <= 0L ||
            signatureHeightEmu <= 0L
        ) {
            throw IOException(
                "The selected signature position is outside the page."
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

        if (!relationshipsXml.contains("</Relationships>")) {
            throw IOException(
                "Invalid DOCX: relationships section is malformed."
            )
        }

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

        if (!Regex(
                """Extension\s*=\s*["']png["']""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(contentTypesXml)
        ) {
            val pngDefault =
                """<Default Extension="png" ContentType="image/png"/>"""

            if (!contentTypesXml.contains("</Types>")) {
                throw IOException(
                    "Invalid DOCX: content types section is malformed."
                )
            }

            entries[contentTypesPath] =
                contentTypesXml.replace(
                    "</Types>",
                    "$pngDefault</Types>"
                ).toByteArray(Charsets.UTF_8)
        }

        // -----------------------------------------------------
        // FLOATING SIGNATURE DRAWING
        // -----------------------------------------------------

        val drawingId = nextDrawingId(documentText)

        val drawingParagraph = createSignatureDrawingParagraph(
            relationshipId = relationshipId,
            imageName = imageName,
            drawingId = drawingId,
            widthEmu = signatureWidthEmu,
            heightEmu = signatureHeightEmu,
            xPositionEmu = xPosition,
            yPositionEmu = yPosition
        )

        // Insert before section properties when present.
        // Otherwise insert before the body closes.
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
     * Creates a floating WordprocessingML signature image.
     *
     * X and Y are positioned relative to the page.
     */
    private fun createSignatureDrawingParagraph(
        relationshipId: String,
        imageName: String,
        drawingId: Long,
        widthEmu: Long,
        heightEmu: Long,
        xPositionEmu: Long,
        yPositionEmu: Long
    ): String {

        return """
<w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
     xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
     xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
     xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
     xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
    <w:r>
        <w:drawing>
            <wp:anchor
                distT="0"
                distB="0"
                distL="0"
                distR="0"
                simplePos="0"
                relativeHeight="251658240"
                behindDoc="0"
                locked="0"
                layoutInCell="1"
                allowOverlap="1">

                <wp:simplePos x="0" y="0"/>

                <wp:positionH relativeFrom="page">
                    <wp:posOffset>$xPositionEmu</wp:posOffset>
                </wp:positionH>

                <wp:positionV relativeFrom="page">
                    <wp:posOffset>$yPositionEmu</wp:posOffset>
                </wp:positionV>

                <wp:extent cx="$widthEmu" cy="$heightEmu"/>
                <wp:effectExtent l="0" t="0" r="0" b="0"/>
                <wp:wrapNone/>

                <wp:docPr
                    id="$drawingId"
                    name="ReceiptShield Signature"/>

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
            </wp:anchor>
        </w:drawing>
    </w:r>
</w:p>
""".trimIndent()
    }

    /**
     * Reads page width and height from DOCX section properties.
     * Uses A4 dimensions if the values cannot be found.
     */
    private fun getPageSizeTwips(xml: String): Pair<Long, Long> {

        val pageSizeRegex = Regex(
            """<w:pgSz\b[^>]*>""",
            RegexOption.IGNORE_CASE
        )

        val pageSizeTag = pageSizeRegex.find(xml)?.value
            ?: return Pair(
                DEFAULT_PAGE_WIDTH_TWIPS,
                DEFAULT_PAGE_HEIGHT_TWIPS
            )

        val widthRegex = Regex(
            """w:w\s*=\s*["'](\d+)["']"""
        )

        val heightRegex = Regex(
            """w:h\s*=\s*["'](\d+)["']"""
        )

        val width = widthRegex.find(pageSizeTag)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: DEFAULT_PAGE_WIDTH_TWIPS

        val height = heightRegex.find(pageSizeTag)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: DEFAULT_PAGE_HEIGHT_TWIPS

        return Pair(width, height)
    }

    private fun createUniqueImageName(
        entries: Map<String, ByteArray>
    ): String {

        var index = 1

        while (
            entries.containsKey(
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

    private fun nextDrawingId(xml: String): Long {

        val regex = Regex("""<wp:docPr[^>]*\bid="(\d+)"""")

        val highest = regex.findAll(xml)
            .mapNotNull {
                it.groupValues.getOrNull(1)?.toLongOrNull()
            }
            .maxOrNull() ?: 0L

        return highest + 1L
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
