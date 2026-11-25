
package com.ali.receiptshield.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ali.receiptshield.crypto.DocumentFormatV4
import com.ali.receiptshield.crypto.InjectedArtifactType
import com.ali.receiptshield.crypto.InjectedArtifactV4
import com.ali.receiptshield.crypto.IntegrityManifestV4
import com.ali.receiptshield.crypto.PlacementMetaV4
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.crypto.RsV4IntegrityManager
import com.ali.receiptshield.crypto.SignatureMetaV4
import com.ali.receiptshield.crypto.StampModeV4
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
    private val securityManager: ReceiptSecurityManager,
    private val integrityManager: RsV4IntegrityManager = RsV4IntegrityManager(securityManager)
) {

    init {
        PDFBoxResourceLoader.init(context)
    }

    // ---------------------------------------------------------
    // RS_V4 INTEGRATED PDF & DOCX STAMPING
    // ---------------------------------------------------------

    fun stampPdfV4(
        originalBytes: ByteArray,
        receiptId: String,
        originalDocSha256: String,
        issuerId: String,
        stampMode: StampModeV4,
        qrBitmap: Bitmap?,
        qrPlacement: QrPlacement?,
        signature: DocumentSignature?,
        signatureMeta: SignatureMetaV4?,
        outputFile: File
    ): IntegrityManifestV4 {
        val tempStampedFile = File.createTempFile("rs_v4_pdf_", ".pdf", context.cacheDir)

        try {
            when (stampMode) {
                StampModeV4.QR_ONLY -> {
                    requireNotNull(qrBitmap) {
                        "QR bitmap is required for QR_ONLY mode."
                    }

                    if (qrPlacement != null) {
                        stampPdfWithQrPlacement(
                            originalBytes,
                            qrBitmap,
                            qrPlacement,
                            tempStampedFile
                        )
                    } else {
                        stampPdf(
                            originalBytes,
                            qrBitmap,
                            tempStampedFile
                        )
                    }
                }

                StampModeV4.SIGNATURE_ONLY -> {
                    requireNotNull(signature) {
                        "DocumentSignature is required for SIGNATURE_ONLY mode."
                    }

                    stampPdfSignatureOnly(
                        originalBytes,
                        signature,
                        tempStampedFile
                    )
                }

                StampModeV4.QR_AND_SIGNATURE -> {
                    requireNotNull(qrBitmap) {
                        "QR bitmap is required for QR_AND_SIGNATURE mode."
                    }

                    requireNotNull(signature) {
                        "DocumentSignature is required for QR_AND_SIGNATURE mode."
                    }

                    if (qrPlacement != null) {
                        stampPdfWithSignatureAndQrPlacement(
                            originalBytes = originalBytes,
                            qrBitmap = qrBitmap,
                            qrPlacement = qrPlacement,
                            signature = signature,
                            outputFile = tempStampedFile
                        )
                    } else {
                        stampPdfWithSignature(
                            originalBytes = originalBytes,
                            qrBitmap = qrBitmap,
                            signature = signature,
                            outputFile = tempStampedFile
                        )
                    }
                }

                else -> throw IllegalArgumentException(
                    "Unsupported stamp mode for PDF: $stampMode"
                )
            }

            val stampedBytes = tempStampedFile.readBytes()

            val renderedDigest =
                integrityManager.computePdfRenderedPagesDigest(stampedBytes)

            val placementMeta = qrPlacement?.toPlacementMetaV4()

            val resolvedSignatureMeta =
                resolveSignatureMetaForPdf(signature, signatureMeta)

            val manifest = integrityManager.buildManifest(
                receiptId = receiptId,
                documentFormat = DocumentFormatV4.PDF,
                stampMode = stampMode,
                originalDocSha256 = originalDocSha256,
                issuerId = issuerId,
                qrPlacement = placementMeta,
                signatureMeta = resolvedSignatureMeta,
                injectedArtifacts = listOf(
                    InjectedArtifactV4(
                        type = InjectedArtifactType.METADATA_STREAM,
                        identifier = "pdf_rendered_pages",
                        digestSha256 = renderedDigest
                    )
                )
            )

            // Sign the completed manifest after all artifact hashes are known.
            val signedManifest =
                integrityManager.signCompletedManifest(manifest)

            // Embed the signed manifest into the PDF.
            val finalBytes = integrityManager.embedManifestInPdfBytes(
                stampedBytes,
                signedManifest
            )

            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(finalBytes)

            return signedManifest

        } finally {
            if (tempStampedFile.exists()) {
                tempStampedFile.delete()
            }
        }
    }

    private fun resolveSignatureMetaForPdf(
        signature: DocumentSignature?,
        providedMeta: SignatureMetaV4?
    ): SignatureMetaV4? {
        if (signature == null || !signature.isSignatureFileAvailable()) {
            return providedMeta
        }

        val sigDigest = providedMeta
            ?.signatureDigestSha256
            ?.takeIf { it.isNotBlank() }
            ?: securityManager.sha256Bytes(
                signature.signatureFile.readBytes()
            )

        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(
            signature.signatureFile.absolutePath,
            opts
        )

        val aspectRatio =
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                opts.outHeight.toFloat() / opts.outWidth.toFloat()
            } else {
                0.35f
            }

        return SignatureMetaV4(
            placement = signature.placement.toPlacementMetaV4(aspectRatio),
            signatureDigestSha256 = sigDigest
        )
    }

    fun stampDocxV4(
        originalBytes: ByteArray,
        receiptId: String,
        originalDocSha256: String,
        issuerId: String,
        stampMode: StampModeV4,
        qrBitmap: Bitmap?,
        qrPlacement: QrPlacement?,
        signatureMeta: SignatureMetaV4?,
        outputFile: File
    ): IntegrityManifestV4 {

        requireNotNull(qrBitmap) {
            "DOCX RS_V4 manifest stamping currently requires a QR bitmap."
        }

        val entries = unzip(originalBytes)

        val documentXml = entries["word/document.xml"]
            ?: throw IllegalArgumentException(
                "Invalid DOCX file: document.xml was not found."
            )

        val signatureArtifacts = entries.entries
            .filter { (entryName, _) ->
                entryName.startsWith(
                    "word/media/receiptshield_signature_"
                ) && !entryName.endsWith("/")
            }
            .map { (entryName, entryBytes) ->
                InjectedArtifactV4(
                    type = InjectedArtifactType.SIGNATURE_IMAGE,
                    identifier = entryName,
                    digestSha256 = securityManager.sha256Bytes(entryBytes)
                )
            }

        val relationshipPath = "word/_rels/document.xml.rels"

        val relationshipsXml = entries[relationshipPath]
            ?.toString(Charsets.UTF_8)
            ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""

        val relationshipId = nextRelationshipId(relationshipsXml)

        val qrPngBytes = bitmapToPng(qrBitmap)

        val imagePath = RsV4IntegrityManager.DOCX_QR_ENTRY

        entries[imagePath] = qrPngBytes

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

        val drawingParagraph = if (qrPlacement != null) {
            val pageSize = getDocxPageSize(documentText)
            val safe = qrPlacement.normalized()

            val widthEmu = (pageSize.first * safe.width).toLong()
            val heightEmu = widthEmu
            val xEmu = (pageSize.first * safe.x).toLong()
            val yEmu = (pageSize.second * safe.y).toLong()

            if (
                xEmu + widthEmu > pageSize.first ||
                yEmu + heightEmu > pageSize.second
            ) {
                throw IllegalArgumentException(
                    "QR code does not fit inside the DOCX page."
                )
            }

            createQrAnchoredDrawingParagraph(
                relationshipId,
                xEmu,
                yEmu,
                widthEmu,
                heightEmu
            )
        } else {
            createQrDrawingParagraph(relationshipId)
        }

        entries["word/document.xml"] = replaceLastOccurrence(
            documentText,
            "</w:body>",
            "$drawingParagraph</w:body>"
        ).toByteArray(Charsets.UTF_8)

        val placementMeta = qrPlacement?.toPlacementMetaV4()

        val artifacts = buildList {
            addAll(signatureArtifacts)

            add(
                InjectedArtifactV4(
                    type = InjectedArtifactType.QR_IMAGE,
                    identifier = imagePath,
                    digestSha256 = securityManager.sha256Bytes(qrPngBytes)
                )
            )

            add(
                InjectedArtifactV4(
                    type = InjectedArtifactType.RELATIONSHIP,
                    identifier = relationshipPath,
                    digestSha256 = securityManager.sha256Bytes(
                        entries[relationshipPath] ?: ByteArray(0)
                    )
                )
            )

            add(
                InjectedArtifactV4(
                    type = InjectedArtifactType.CONTENT_TYPE,
                    identifier = "[Content_Types].xml",
                    digestSha256 = securityManager.sha256Bytes(
                        entries["[Content_Types].xml"] ?: ByteArray(0)
                    )
                )
            )
        }

        val manifest = integrityManager.buildManifest(
            receiptId = receiptId,
            documentFormat = DocumentFormatV4.DOCX,
            stampMode = stampMode,
            originalDocSha256 = originalDocSha256,
            issuerId = issuerId,
            qrPlacement = placementMeta,
            signatureMeta = signatureMeta,
            injectedArtifacts = artifacts
        )

        // Sign the completed manifest after all DOCX artifact hashes are known.
        val signedManifest =
            integrityManager.signCompletedManifest(manifest)

        // Embed the signed manifest into the DOCX.
        integrityManager.embedManifestInDocxEntries(
            entries,
            signedManifest
        )

        writeDocx(entries, outputFile)

        return signedManifest
    }

    // ---------------------------------------------------------
    // PDF QR STAMPING
    // ---------------------------------------------------------

    fun stampPdf(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        outputFile: File
    ) {
        val document = PDDocument.load(
            ByteArrayInputStream(originalBytes)
        )

        try {
            if (document.numberOfPages == 0) {
                document.addPage(PDPage())
            }

            val page = document.getPage(
                document.numberOfPages - 1
            )

            val pageBox = page.mediaBox
            val qrImage = LosslessFactory.createFromImage(
                document,
                qrBitmap
            )

            val qrSize = 60f
            val margin = 24f

            val x = (pageBox.width - qrSize - margin)
                .coerceAtLeast(0f)

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

    fun stampPdfWithQrPlacement(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        placement: QrPlacement,
        outputFile: File
    ) {
        val document = PDDocument.load(
            ByteArrayInputStream(originalBytes)
        )

        try {
            if (document.numberOfPages == 0) {
                document.addPage(PDPage())
            }

            val safePlacement = placement.normalized()

            if (
                safePlacement.pageIndex !in
                0 until document.numberOfPages
            ) {
                throw IllegalArgumentException(
                    "Selected QR page does not exist."
                )
            }

            val page = document.getPage(
                safePlacement.pageIndex
            )

            val pageBox = page.mediaBox

            val qrSize = pageBox.width * safePlacement.width
            val x = pageBox.width * safePlacement.x
            val top = pageBox.height * safePlacement.y
            val y = pageBox.height - top - qrSize

            if (
                x < 0f ||
                y < 0f ||
                x + qrSize > pageBox.width ||
                y + qrSize > pageBox.height
            ) {
                throw IllegalArgumentException(
                    "QR code does not fit on the selected page."
                )
            }

            val qrImage = LosslessFactory.createFromImage(
                document,
                qrBitmap
            )

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

    fun stampPdfSignatureOnly(
        originalBytes: ByteArray,
        signature: DocumentSignature,
        outputFile: File
    ) {
        if (!signature.isSignatureFileAvailable()) {
            throw IOException(
                "Signature image file is missing or empty."
            )
        }

        val signatureBitmap = BitmapFactory.decodeFile(
            signature.signatureFile.absolutePath
        ) ?: throw IOException(
            "Unable to decode signature image."
        )

        try {
            val document = PDDocument.load(
                ByteArrayInputStream(originalBytes)
            )

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
    // PDF QR + SIGNATURE
    // ---------------------------------------------------------

    fun stampPdfWithSignatureAndQrPlacement(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        qrPlacement: QrPlacement,
        signature: DocumentSignature,
        outputFile: File
    ) {
        if (!signature.isSignatureFileAvailable()) {
            throw IOException(
                "Signature image file is missing or empty."
            )
        }

        val signatureBitmap = BitmapFactory.decodeFile(
            signature.signatureFile.absolutePath
        ) ?: throw IOException(
            "Unable to decode signature image."
        )

        try {
            val document = PDDocument.load(
                ByteArrayInputStream(originalBytes)
            )

            try {
                if (document.numberOfPages == 0) {
                    document.addPage(PDPage())
                }

                val safeQrPlacement = qrPlacement.normalized()

                if (
                    safeQrPlacement.pageIndex !in
                    0 until document.numberOfPages
                ) {
                    throw IllegalArgumentException(
                        "Selected QR page does not exist."
                    )
                }

                val qrPage = document.getPage(
                    safeQrPlacement.pageIndex
                )

                val qrPageBox = qrPage.mediaBox

                val qrSize = qrPageBox.width *
                    safeQrPlacement.width

                val qrX = qrPageBox.width *
                    safeQrPlacement.x

                val qrTop = qrPageBox.height *
                    safeQrPlacement.y

                val qrY = qrPageBox.height -
                    qrTop -
                    qrSize

                if (
                    qrX < 0f ||
                    qrY < 0f ||
                    qrX + qrSize > qrPageBox.width ||
                    qrY + qrSize > qrPageBox.height
                ) {
                    throw IllegalArgumentException(
                        "QR code does not fit on the selected page."
                    )
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

    fun stampPdfWithSignature(
        originalBytes: ByteArray,
        qrBitmap: Bitmap,
        signature: DocumentSignature,
        outputFile: File
    ) {
        if (!signature.isSignatureFileAvailable()) {
            throw IOException(
                "Signature image file is missing or empty."
            )
        }

        val signatureBitmap = BitmapFactory.decodeFile(
            signature.signatureFile.absolutePath
        ) ?: throw IOException(
            "Unable to decode signature image."
        )

        try {
            val document = PDDocument.load(
                ByteArrayInputStream(originalBytes)
            )

            try {
                if (document.numberOfPages == 0) {
                    document.addPage(PDPage())
                }

                val qrPage = document.getPage(
                    document.numberOfPages - 1
                )

                val qrPageBox = qrPage.mediaBox

                val qrImage = LosslessFactory.createFromImage(
                    document,
                    qrBitmap
                )

                val qrSize = 60f
                val margin = 24f

                val qrX = (
                    qrPageBox.width - qrSize - margin
                ).coerceAtLeast(0f)

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

        if (
            placement.pageIndex !in
            0 until document.numberOfPages
        ) {
            throw IllegalArgumentException(
                "Selected signature page does not exist."
            )
        }

        val page = document.getPage(
            placement.pageIndex
        )

        val pageBox = page.mediaBox

        val bounds = SignaturePlacementProcessor.calculateBounds(
            placement,
            pageBox.width,
            pageBox.height,
            signatureBitmap
        )

        if (
            !SignaturePlacementProcessor.fitsOnPage(
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

        val pdfY = pageBox.lowerLeftY +
            pageBox.height -
            bounds.bottom

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
            ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""

        val imagePath = "word/media/receiptshield_qr.png"

        val relationshipId = nextRelationshipId(
            relationshipsXml
        )

        entries[imagePath] = bitmapToPng(qrBitmap)

        entries[relationshipPath] = addImageRelationship(
            relationshipsXml,
            relationshipId,
            "media/receiptshield_qr.png"
        ).toByteArray(Charsets.UTF_8)

        addPngContentType(entries)

        val drawingParagraph =
            createQrDrawingParagraph(relationshipId)

        val documentText = documentXml.toString(
            Charsets.UTF_8
        )

        if (!documentText.contains("</w:body>")) {
            throw IllegalArgumentException(
                "Invalid DOCX structure: document body was not found."
            )
        }

        entries["word/document.xml"] = replaceLastOccurrence(
            documentText,
            "</w:body>",
            "$drawingParagraph</w:body>"
        ).toByteArray(Charsets.UTF_8)

        writeDocx(entries, outputFile)
    }

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
            ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""

        val relationshipId = nextRelationshipId(
            relationshipsXml
        )

        entries["word/media/receiptshield_qr.png"] =
            bitmapToPng(qrBitmap)

        entries[relationshipPath] = addImageRelationship(
            relationshipsXml,
            relationshipId,
            "media/receiptshield_qr.png"
        ).toByteArray(Charsets.UTF_8)

        addPngContentType(entries)

        val documentText = documentXml.toString(
            Charsets.UTF_8
        )

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

        if (
            xEmu + widthEmu > pageSize.first ||
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

        entries["word/document.xml"] = replaceLastOccurrence(
            documentText,
            "</w:body>",
            "$drawing</w:body>"
        ).toByteArray(Charsets.UTF_8)

        writeDocx(entries, outputFile)
    }

    // ---------------------------------------------------------
    // QR EXTRACTION
    // ---------------------------------------------------------

    fun extractQrFromPdf(
        pdfBytes: ByteArray,
        placementMeta: PlacementMetaV4? = null
    ): String? {
        val document = PDDocument.load(
            ByteArrayInputStream(pdfBytes)
        )

        try {
            val renderer = PDFRenderer(document)

            for (pageIndex in 0 until document.numberOfPages) {
                val bitmap = renderer.renderImageWithDPI(
                    pageIndex,
                    200f
                )

                try {
                    if (
                        placementMeta != null &&
                        placementMeta.pageIndex == pageIndex
                    ) {
                        val cropped = cropBitmapToPlacement(
                            bitmap,
                            placementMeta
                        )

                        if (cropped != null) {
                            try {
                                val result =
                                    securityManager.decodeQRCode(cropped)

                                if (!result.isNullOrBlank()) {
                                    return result
                                }
                            } finally {
                                cropped.recycle()
                            }
                        }
                    }

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

    fun extractQrFromDocx(
        docxBytes: ByteArray,
        qrEntryPath: String? = null
    ): String? {
        val entries = unzip(docxBytes)

        if (
            qrEntryPath != null &&
            entries.containsKey(qrEntryPath)
        ) {
            val bytes = entries[qrEntryPath]

            if (bytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size
                )

                if (bitmap != null) {
                    try {
                        val result =
                            securityManager.decodeQRCode(bitmap)

                        if (!result.isNullOrBlank()) {
                            return result
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }

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
                    val result =
                        securityManager.decodeQRCode(bitmap)

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

    fun cropBitmapToPlacement(
        bitmap: Bitmap,
        placement: PlacementMetaV4
    ): Bitmap? {
        return try {
            val x = (placement.xRatio * bitmap.width)
                .toInt()
                .coerceIn(0, bitmap.width - 1)

            val y = (placement.yRatio * bitmap.height)
                .toInt()
                .coerceIn(0, bitmap.height - 1)

            val w = (placement.widthRatio * bitmap.width)
                .toInt()
                .coerceIn(1, bitmap.width - x)

            val h = (placement.heightRatio * bitmap.height)
                .toInt()
                .coerceIn(1, bitmap.height - y)

            val margin = (w * 0.1f)
                .toInt()
                .coerceAtLeast(20)

            val cropX = (x - margin).coerceAtLeast(0)
            val cropY = (y - margin).coerceAtLeast(0)

            val cropW = (w + margin * 2)
                .coerceAtMost(bitmap.width - cropX)

            val cropH = (h + margin * 2)
                .coerceAtMost(bitmap.height - cropY)

            Bitmap.createBitmap(
                bitmap,
                cropX,
                cropY,
                cropW,
                cropH
            )

        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------
    // ZIP AND IMAGE UTILITIES
    // ---------------------------------------------------------

    private fun replaceLastOccurrence(
        source: String,
        target: String,
        replacement: String
    ): String {
        val idx = source.lastIndexOf(target)

        if (idx == -1) {
            return source
        }

        return source.substring(0, idx) +
            replacement +
            source.substring(idx + target.length)
    }

    private fun createQrAnchoredDrawingParagraph(
        relationshipId: String,
        x: Long,
        y: Long,
        width: Long,
        height: Long
    ): String {
        return """
<w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
    <w:r><w:drawing><wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="251658240" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1">
        <wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="page"><wp:posOffset>$x</wp:posOffset></wp:positionH><wp:positionV relativeFrom="page"><wp:posOffset>$y</wp:posOffset></wp:positionV>
        <wp:extent cx="$width" cy="$height"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapNone/><wp:docPr id="9001" name="ReceiptShield QR Code"/><wp:cNvGraphicFramePr/>
        <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>
            <pic:nvPicPr><pic:cNvPr id="0" name="receiptshield_qr.png"/><pic:cNvPicPr/></pic:nvPicPr>
            <pic:blipFill><a:blip r:embed="$relationshipId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
            <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>
        </pic:pic></a:graphicData></a:graphic>
    </wp:anchor></w:drawing></w:r>
</w:p>
""".trimIndent()
    }

    private fun getDocxPageSize(
        xml: String
    ): Pair<Long, Long> {
        val section = Regex(
            """<w:pgSz\b[^>]*>"""
        ).find(xml)?.value

        val widthTwips = section
            ?.let {
                Regex("""w:w="(\d+)"""")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            } ?: 11906L

        val heightTwips = section
            ?.let {
                Regex("""w:h="(\d+)"""")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            } ?: 16838L

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
            entries[path] = xml.replace(
                "</Types>",
                """<Default Extension="png" ContentType="image/png"/></Types>"""
            ).toByteArray(Charsets.UTF_8)
        }
    }

    private fun writeDocx(
        entries: LinkedHashMap<String, ByteArray>,
        outputFile: File
    ) {
        outputFile.parentFile?.mkdirs()

        ZipOutputStream(
            outputFile.outputStream()
        ).use { zip ->
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
<w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
    <w:r><w:t>ReceiptShield verification QR code:</w:t></w:r>
    <w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">
        <wp:extent cx="600000" cy="600000"/><wp:docPr id="9001" name="ReceiptShield QR Code"/><wp:cNvGraphicFramePr/>
        <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>
            <pic:nvPicPr><pic:cNvPr id="0" name="receiptshield_qr.png"/><pic:cNvPicPr/></pic:nvPicPr>
            <pic:blipFill><a:blip r:embed="$relationshipId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
            <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="600000" cy="600000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>
        </pic:pic></a:graphicData></a:graphic>
    </wp:inline></w:drawing></w:r>
</w:p>
""".trimIndent()
    }

    private fun addImageRelationship(
        relationshipsXml: String,
        relationshipId: String,
        target: String
    ): String {
        return relationshipsXml.replace(
            "</Relationships>",
            """<Relationship Id="$relationshipId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="$target"/></Relationships>"""
        )
    }

    private fun nextRelationshipId(xml: String): String {
        val highest = Regex(
            """Id="rId(\d+)""""
        ).findAll(xml)
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

        ZipInputStream(
            ByteArrayInputStream(bytes)
        ).use { zip ->
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

    private fun bitmapToPng(
        bitmap: Bitmap
    ): ByteArray {
        val output = ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.PNG,
            100,
            output
        )

        return output.toByteArray()
    }
}
