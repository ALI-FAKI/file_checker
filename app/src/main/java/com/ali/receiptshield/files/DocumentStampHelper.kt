
package com.ali.receiptshield.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.qr.QrPlacement
import com.ali.receiptshield.signature.DocumentSignature
import com.ali.receiptshield.signature.SignaturePlacementProcessor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
    // PDF QR STAMPING
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

            val qrImage = LosslessFactory.createFromImage(document, qrBitmap)

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
                    pageBox.lowerLeftX + x,
                    pageBox.lowerLeftY + y,
                    qrSize,
                    qrSize
                )
            }

            savePdf(document, outputFile)

        } finally {
            document.close()
        }
    }

    /**
     * Adds a QR code to a PDF using the placement selected by the user.
     * Coordinates are normalized from the top-left of the page.
     */
    fun stampPdfWithQrPlacement(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        placement: QrPlacement,
        outputFile: File
    ) {
        val document = PDDocument.load(ByteArrayInputStream(originalBytes))

        try {
            if (document.numberOfPages == 0) {
                document.addPage(PDPage())
            }

            val safePlacement = placement.normalized()

            if (safePlacement.pageIndex !in 0 until document.numberOfPages) {
                throw IllegalArgumentException("Selected QR page does not exist.")
            }

            val page = document.getPage(safePlacement.pageIndex)
            val pageBox = page.mediaBox

            val qrSize = pageBox.width * safePlacement.width
            val x = pageBox.width * safePlacement.x
            val top = pageBox.height * safePlacement.y
            val y = pageBox.height - top - qrSize

            if (x < 0f || y < 0f ||
                x + qrSize > pageBox.width ||
                y + qrSize > pageBox.height
            ) {
                throw IllegalArgumentException("QR code does not fit on the selected page.")
            }

            val qrImage = LosslessFactory.createFromImage(document, qrBitmap)

            PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { stream ->
                stream.drawImage(
                    qrImage,
                    pageBox.lowerLeftX + x,
                    pageBox.lowerLeftY + y,
                    qrSize,
                    qrSize
                )
            }

            savePdf(document, outputFile)

        } finally {
            document.close()
        }
    }

    // ---------------------------------------------------------
    // PDF SIGNATURE ONLY
    // ---------------------------------------------------------

    /**
     * Adds a signature to a PDF without generating or inserting a QR code.
     */
    fun stampPdfSignatureOnly(
        originalBytes: ByteArray,
        signature: DocumentSignature,
        outputFile: File
    ) {
        if (!signature.isSignatureFileAvailable()) {
            throw IOException("Signature image file is missing or empty.")
        }

        val signatureBitmap = BitmapFactory.decodeFile(
            signature.signatureFile.absolutePath
        ) ?: throw IOException("Unable to decode signature image.")

        try {
            val document = PDDocument.load(ByteArrayInputStream(originalBytes))

            try {
                if (document.numberOfPages == 0) {
                    document.addPage(PDPage())
                }

                drawSignature(
                    document,
                    signatureBitmap,
                    signature
                )

                savePdf(document, outputFile)

            } finally {
                document.close()
            }

        } finally {
            signatureBitmap.recycle()
        }
    }

    // ---------------------------------------------------------
    // PDF QR + SIGNATURE WITH INDEPENDENT QR PLACEMENT
    // ---------------------------------------------------------

    fun stampPdfWithSignatureAndQrPlacement(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        qrPlacement: QrPlacement,
        signature: DocumentSignature,
        outputFile: File
    ) {
        if (!signature.isSignatureFileAvailable()) {
            throw IOException("Signature image file is missing or empty.")
        }

        val signatureBitmap = BitmapFactory.decodeFile(
            signature.signatureFile.absolutePath
        ) ?: throw IOException("Unable to decode signature image.")

        try {
            val document = PDDocument.load(ByteArrayInputStream(originalBytes))

            try {
                if (document.numberOfPages == 0) {
                    document.addPage(PDPage())
                }

                val safeQrPlacement = qrPlacement.normalized()

                if (safeQrPlacement.pageIndex !in 0 until document.numberOfPages) {
                    throw IllegalArgumentException("Selected QR page does not exist.")
                }

                val qrPage = document.getPage(safeQrPlacement.pageIndex)
                val qrPageBox = qrPage.mediaBox

                val qrSize = qrPageBox.width * safeQrPlacement.width
                val qrX = qrPageBox.width * safeQrPlacement.x
                val qrTop = qrPageBox.height * safeQrPlacement.y
                val qrY = qrPageBox.height - qrTop - qrSize

                if (qrX < 0f || qrY < 0f ||
                    qrX + qrSize > qrPageBox.width ||
                    qrY + qrSize > qrPageBox.height
                ) {
                    throw IllegalArgumentException("QR code does not fit on the selected page.")
                }

                val qrImage = LosslessFactory.createFromImage(
                    document,
                    qrBitmap
                )

                PDPageContentStream(
                    document,
                    qrPage,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { stream ->
                    stream.drawImage(
                        qrImage,
                        qrPageBox.lowerLeftX + qrX,
                        qrPageBox.lowerLeftY + qrY,
                        qrSize,
                        qrSize
                    )
                }

                drawSignature(
                    document,
                    signatureBitmap,
                    signature
                )

                savePdf(document, outputFile)

            } finally {
                document.close()
            }

        } finally {
            signatureBitmap.recycle()
        }
    }

    // ---------------------------------------------------------
    // EXISTING PDF QR + SIGNATURE METHOD
    // ---------------------------------------------------------

    fun stampPdfWithSignature(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        signature: DocumentSignature,
        outputFile: File
    ) {
        if (!signature.isSignatureFileAvailable()) {
            throw IOException("Signature image file is missing or empty.")
        }

        val signatureBitmap = BitmapFactory.decodeFile(
            signature.signatureFile.absolutePath
        ) ?: throw IOException("Unable to decode signature image.")

        try {
            val document = PDDocument.load(ByteArrayInputStream(originalBytes))

            try {
                if (document.numberOfPages == 0) {
                    document.addPage(PDPage())
                }

                val qrPage = document.getPage(document.numberOfPages - 1)
                val qrPageBox = qrPage.mediaBox
                val qrImage = LosslessFactory.createFromImage(document, qrBitmap)

                val qrSize = 60f
                val margin = 24f

                val qrX = (qrPageBox.width - qrSize - margin).coerceAtLeast(0f)
                val qrY = margin

                PDPageContentStream(
                    document,
                    qrPage,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { stream ->
                    stream.drawImage(
                        qrImage,
                        qrPageBox.lowerLeftX + qrX,
                        qrPageBox.lowerLeftY + qrY,
                        qrSize,
                        qrSize
                    )
                }

                drawSignature(
                    document,
                    signatureBitmap,
                    signature
                )

                savePdf(document, outputFile)

            } finally {
                document.close()
            }

        } finally {
            signatureBitmap.recycle()
        }
    }

    private fun drawSignature(
        document: PDDocument,
        signatureBitmap: Bitmap,
        signature: DocumentSignature
    ) {
        val placement = signature.placement.normalized()

        if (placement.pageIndex !in 0 until document.numberOfPages) {
            throw IllegalArgumentException("Selected signature page does not exist.")
        }

        val page = document.getPage(placement.pageIndex)
        val pageBox = page.mediaBox

        val bounds = SignaturePlacementProcessor.calculateBounds(
            placement = placement,
            pageWidth = pageBox.width,
            pageHeight = pageBox.height,
            signatureBitmap = signatureBitmap
        )

        if (!SignaturePlacementProcessor.fitsOnPage(
                bounds,
                pageBox.width,
                pageBox.height
            )
        ) {
            throw IllegalArgumentException(
                "Signature does not fit inside the selected PDF page."
            )
        }

        val image = LosslessFactory.createFromImage(
            document,
            signatureBitmap
        )

        val pdfX = pageBox.lowerLeftX + bounds.left
        val pdfY = pageBox.lowerLeftY + pageBox.height - bounds.bottom

        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { stream ->
            stream.drawImage(
                image,
                pdfX,
                pdfY,
                bounds.width,
                bounds.height
            )
        }
    }

    private fun savePdf(
        document: PDDocument,
        outputFile: File
    ) {
        outputFile.parentFile?.mkdirs()
        document.save(outputFile)
    }

    // ---------------------------------------------------------
    // DOCX QR STAMPING - EXISTING DEFAULT METHOD
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

        entries[imagePath] = bitmapToPng(qrBitmap)

        entries[relationshipPath] = addImageRelationship(
            relationshipsXml,
            relationshipId,
            "media/receiptshield_qr.png"
        ).toByteArray(Charsets.UTF_8)

        addPngContentType(entries)

        val drawingParagraph = createQrDrawingParagraph(relationshipId)
        val documentText = documentXml.toString(Charsets.UTF_8)

        if (!documentText.contains("</w:body>")) {
            throw IllegalArgumentException(
                "Invalid DOCX structure: document body was not found."
            )
        }

        entries["word/document.xml"] = documentText.replace(
            "</w:body>",
            "$drawingParagraph</w:body>"
        ).toByteArray(Charsets.UTF_8)

        writeDocx(entries, outputFile)
    }

    // ---------------------------------------------------------
    // DOCX QR POSITIONING
    // ---------------------------------------------------------

    /**
     * Inserts a floating QR image using page-relative coordinates.
     *
     * DOCX page layout can reflow in different Word applications.
     * This method uses page-relative anchoring, but exact page
     * selection still requires further DOCX layout testing.
     */
    fun stampDocxWithQrPlacement(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        placement: QrPlacement,
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

        val relationshipId = nextRelationshipId(relationshipsXml)

        entries["word/media/receiptshield_qr.png"] = bitmapToPng(qrBitmap)

        entries[relationshipPath] = addImageRelationship(
            relationshipsXml,
            relationshipId,
            "media/receiptshield_qr.png"
        ).toByteArray(Charsets.UTF_8)

        addPngContentType(entries)

        val documentText = documentXml.toString(Charsets.UTF_8)

        if (!documentText.contains("</w:body>")) {
            throw IllegalArgumentException(
                "Invalid DOCX structure: document body was not found."
            )
        }

        val pageSize = getDocxPageSize(documentText)
        val safe = placement.normalized()

        val widthEmu = (pageSize.first * safe.width).toLong()
        val heightEmu = widthEmu
        val xEmu = (pageSize.first * safe.x).toLong()
        val yEmu = (pageSize.second * safe.y).toLong()

        if (xEmu + widthEmu > pageSize.first ||
            yEmu + heightEmu > pageSize.second
        ) {
            throw IllegalArgumentException(
                "QR code does not fit inside the DOCX page."
            )
        }

        val drawing = createQrAnchoredDrawingParagraph(
            relationshipId,
            xEmu,
            yEmu,
            widthEmu,
            heightEmu
        )

        entries["word/document.xml"] = documentText.replace(
            "</w:body>",
            "$drawing</w:body>"
        ).toByteArray(Charsets.UTF_8)

        writeDocx(entries, outputFile)
    }

    private fun createQrAnchoredDrawingParagraph(
        relationshipId: String,
        x: Long,
        y: Long,
        width: Long,
        height: Long
    ): String {
        return """
<w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
     xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
     xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
     xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
     xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
    <w:r>
        <w:drawing>
            <wp:anchor distT="0" distB="0" distL="0" distR="0"
                       simplePos="0" relativeHeight="251658240"
                       behindDoc="0" locked="0" layoutInCell="1"
                       allowOverlap="1">
                <wp:simplePos x="0" y="0"/>
                <wp:positionH relativeFrom="page">
                    <wp:posOffset>$x</wp:posOffset>
                </wp:positionH>
                <wp:positionV relativeFrom="page">
                    <wp:posOffset>$y</wp:posOffset>
                </wp:positionV>
                <wp:extent cx="$width" cy="$height"/>
                <wp:effectExtent l="0" t="0" r="0" b="0"/>
                <wp:wrapNone/>
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
                                    <a:ext cx="$width" cy="$height"/>
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

    private fun getDocxPageSize(xml: String): Pair<Long, Long> {
        val section = Regex(
            """<w:pgSz\b[^>]*>"""
        ).find(xml)?.value

        val widthTwips = section?.let {
            Regex("""w:w="(\d+)"""").find(it)
                ?.groupValues?.get(1)?.toLongOrNull()
        } ?: 11906L

        val heightTwips = section?.let {
            Regex("""w:h="(\d+)"""").find(it)
                ?.groupValues?.get(1)?.toLongOrNull()
        } ?: 16838L

        // One twip = 635 EMUs.
        return Pair(
            widthTwips * 635L,
            heightTwips * 635L
        )
    }

    private fun addPngContentType(
        entries: LinkedHashMap<String, ByteArray>
    ) {
        val path = "[Content_Types].xml"

        val xml = entries[path]
            ?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException(
                "Invalid DOCX file: content types information was not found."
            )

        if (!xml.contains("""Extension="png"""")) {
            val pngDefault =
                """<Default Extension="png" ContentType="image/png"/>"""

            entries[path] = xml.replace(
                "</Types>",
                "$pngDefault</Types>"
            ).toByteArray(Charsets.UTF_8)
        }
    }

    private fun writeDocx(
        entries: LinkedHashMap<String, ByteArray>,
        outputFile: File
    ) {
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
            .mapNotNull {
                it.groupValues.getOrNull(1)?.toIntOrNull()
            }
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
                val bitmap = renderer.renderImageWithDPI(pageIndex, 300f)

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
            if (name.startsWith("word/media/") && !name.endsWith("/")) {
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
