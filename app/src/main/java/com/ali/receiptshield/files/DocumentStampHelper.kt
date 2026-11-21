package com.ali.receiptshield.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocumentStampHelper(
    private val context: Context,
    private val securityManager: ReceiptSecurityManager
) {

    init {
        PDFBoxResourceLoader.init(context)
    }

    // ---------------------------------------------------------
    // PDF STAMPING
    // ---------------------------------------------------------

    fun stampPdf(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        outputFile: File
    ) {
        val document = PDDocument.load(ByteArrayInputStream(originalBytes))

        try {
            if (document.numberOfPages == 0) {
                document.addPage(PDPage())
            }

            val page = document.getPage(document.numberOfPages - 1)
            val pageBox = page.mediaBox

            val qrImage = LosslessFactory.createFromImage(
                document,
                qrBitmap
            )

            // FIX: Reduced size from 110f to 60f and margin from 36f to 24f
            val qrSize = 60f
            val margin = 24f

            val x = (pageBox.width - qrSize - margin).coerceAtLeast(0f)
            val y = margin

            PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { stream ->

                stream.drawImage(
                    qrImage,
                    x,
                    y,
                    qrSize,
                    qrSize
                )
            }

            outputFile.parentFile?.mkdirs()
            document.save(outputFile)

        } finally {
            document.close()
        }
    }

    // ---------------------------------------------------------
    // DOCX STAMPING
    // ---------------------------------------------------------

    fun stampDocx(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        outputFile: File
    ) {
        val entries = unzip(originalBytes)

        val documentXml = entries["word/document.xml"]
            ?: throw IllegalArgumentException(
                "Invalid DOCX file: document.xml was not found."
            )

        val relationshipPath = "word/_rels/document.xml.rels"

        val relationshipsXml = entries[relationshipPath]
            ?.toString(Charsets.UTF_8)
            ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""

        val imagePath = "word/media/receiptshield_qr.png"
        val relationshipId = nextRelationshipId(relationshipsXml)

        val imageBytes = bitmapToPng(qrBitmap)

        entries[imagePath] = imageBytes

        val updatedRelationships = addImageRelationship(
            relationshipsXml,
            relationshipId,
            "media/receiptshield_qr.png"
        )

        entries[relationshipPath] = updatedRelationships.toByteArray(Charsets.UTF_8)

        val contentTypesPath = "[Content_Types].xml"
        val contentTypesXml = entries[contentTypesPath]
            ?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException(
                "Invalid DOCX file: content types information was not found."
            )

        if (!contentTypesXml.contains("""Extension="png"""")) {
            val pngDefault =
                """<Default Extension="png" ContentType="image/png"/>"""

            entries[contentTypesPath] = contentTypesXml.replace(
                "</Types>",
                "$pngDefault</Types>"
            ).toByteArray(Charsets.UTF_8)
        }

        val drawingParagraph = createQrDrawingParagraph(relationshipId)

        val documentText = documentXml.toString(Charsets.UTF_8)

        if (!documentText.contains("</w:body>")) {
            throw IllegalArgumentException(
                "Invalid DOCX structure: document body was not found."
            )
        }

        val updatedDocument = documentText.replace(
            "</w:body>",
            "$drawingParagraph</w:body>"
        )

        entries["word/document.xml"] =
            updatedDocument.toByteArray(Charsets.UTF_8)

        outputFile.parentFile?.mkdirs()

        ZipOutputStream(outputFile.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun createQrDrawingParagraph(
        relationshipId: String
    ): String {
        return """
<w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
     xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
     xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
     xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
     xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
    <w:r>
        <w:t>ReceiptShield verification QR code:</w:t>
    </w:r>
    <w:r>
        <w:drawing>
            <wp:inline distT="0" distB="0" distL="0" distR="0">
                <!-- FIX: Changed extent from 1047750 to 600000 to make it smaller -->
                <wp:extent cx="600000" cy="600000"/>
                <wp:docPr id="9001" name="ReceiptShield QR Code"/>
                <wp:cNvGraphicFramePr/>
                <a:graphic>
                    <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                        <pic:pic>
                            <pic:nvPicPr>
                                <pic:cNvPr id="0" name="receiptshield_qr.png"/>
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
                                    <!-- FIX: Changed extent from 1047750 to 600000 -->
                                    <a:ext cx="600000" cy="600000"/>
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

    private fun addImageRelationship(
        relationshipsXml: String,
        relationshipId: String,
        target: String
    ): String {
        val relationship =
            """<Relationship Id="$relationshipId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="$target"/>"""

        return relationshipsXml.replace(
            "</Relationships>",
            "$relationship</Relationships>"
        )
    }

    private fun nextRelationshipId(xml: String): String {
        val regex = Regex("""Id="rId(\d+)"""")

        val highest = regex.findAll(xml)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull() ?: 0

        return "rId${highest + 1}"
    }

    // ---------------------------------------------------------
    // QR EXTRACTION FROM PDF
    // ---------------------------------------------------------

    fun extractQrFromPdf(pdfBytes: ByteArray): String? {
        val document = PDDocument.load(ByteArrayInputStream(pdfBytes))

        try {
            val renderer = PDFRenderer(document)

            for (pageIndex in 0 until document.numberOfPages) {
                val bitmap = renderer.renderImageWithDPI(
                    pageIndex,
                    180f
                )

                try {
                    val result = securityManager.decodeQRCode(bitmap)

                    if (!result.isNullOrBlank()) {
                        return result
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            return null

        } finally {
            document.close()
        }
    }

    // ---------------------------------------------------------
    // QR EXTRACTION FROM DOCX
    // ---------------------------------------------------------

    fun extractQrFromDocx(docxBytes: ByteArray): String? {
        val entries = unzip(docxBytes)

        for ((name, bytes) in entries) {
            if (
                name.startsWith("word/media/") &&
                !name.endsWith("/")
            ) {
                val bitmap = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size
                ) ?: continue

                try {
                    val result = securityManager.decodeQRCode(bitmap)

                    if (!result.isNullOrBlank()) {
                        return result
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        return null
    }

    // ---------------------------------------------------------
    // ZIP AND IMAGE UTILITIES
    // ---------------------------------------------------------

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
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

    private fun bitmapToPng(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.PNG,
            100,
            output
        )

        return output.toByteArray()
    }
}
