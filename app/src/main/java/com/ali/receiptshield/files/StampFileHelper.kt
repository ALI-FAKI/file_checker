package com.ali.receiptshield.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.ali.receiptshield.crypto.RECEIPT_VERSION_V4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StampFileHelper(private val context: Context) {

    fun getFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        cursor.getString(index)?.let { return it }
                    }
                }
            }
        }
        val path = uri.path ?: return "Unknown File"
        val cut = path.lastIndexOf('/')
        return if (cut != -1) path.substring(cut + 1) else path
    }

    fun guessMimeType(uri: Uri, fileName: String): String {
        context.contentResolver.getType(uri)?.let { return it }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "*/*"
    }

    fun isPdfFile(fileName: String, mimeType: String): Boolean {
        return mimeType.equals("application/pdf", ignoreCase = true) ||
            fileName.lowercase(Locale.getDefault()).endsWith(".pdf")
    }

    fun createStampsDirectory(): File {
        val dir = File(context.filesDir, "stamps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Creates a stamped PDF containing a verification page with the RS_V4 QR code,
     * Issuer ID, timestamp, and original file SHA-256 hash.
     */
    fun createStampedPdfWithQr(
        qrBitmap: Bitmap,
        originalFileName: String,
        issuerId: String,
        timestamp: Long,
        fileHash: String,
        payloadSerialized: String,
        outFile: File
    ): File {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val pdfDocument = PdfDocument()

        // Standard A4-like portrait page in points (595 x 842)
        val pageWidth = 595
        val pageHeight = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1C1E")
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5F6368")
            textSize = 12f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#202124")
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C4043")
            textSize = 12f
        }

        canvas.drawText("ReceiptShield $RECEIPT_VERSION_V4 Verification Stamp", 48f, 64f, titlePaint)
        canvas.drawText("Offline Cryptographic Integrity Record ($RECEIPT_VERSION_V4)", 48f, 84f, subtitlePaint)

        // Draw QR code centered
        val qrSize = 240
        val qrLeft = (pageWidth - qrSize) / 2
        val qrTop = 120
        val scaledQr = Bitmap.createScaledBitmap(qrBitmap, qrSize, qrSize, false)
        try {
            canvas.drawBitmap(scaledQr, qrLeft.toFloat(), qrTop.toFloat(), null)
        } finally {
            if (scaledQr !== qrBitmap && !scaledQr.isRecycled) {
                scaledQr.recycle()
            }
        }

        var y = (qrTop + qrSize + 44).toFloat()
        val lineGap = 24f

        canvas.drawText("Document:", 48f, y, labelPaint)
        canvas.drawText(originalFileName, 150f, y, valuePaint)
        y += lineGap

        canvas.drawText("Issuer ID:", 48f, y, labelPaint)
        canvas.drawText(issuerId, 150f, y, valuePaint)
        y += lineGap

        canvas.drawText("Stamped Date:", 48f, y, labelPaint)
        canvas.drawText(formatter.format(Date(timestamp)), 150f, y, valuePaint)
        y += lineGap

        canvas.drawText("SHA-256:", 48f, y, labelPaint)
        canvas.drawText(fileHash.take(36) + "...", 150f, y, valuePaint)
        y += lineGap * 1.5f

        canvas.drawText("QR Payload ($RECEIPT_VERSION_V4):", 48f, y, labelPaint)
        y += 18f
        val chunks = payloadSerialized.chunked(68)
        for (chunk in chunks) {
            canvas.drawText(chunk, 48f, y, valuePaint)
            y += 16f
        }

        pdfDocument.finishPage(page)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
        return outFile
    }

    /**
     * Writes a companion QR image and stamp summary file for RS_V4 workflows.
     */
    fun createDocumentQrArtifacts(
        qrBitmap: Bitmap,
        cleanBase: String,
        stampSuffix: Long,
        originalFileName: String,
        issuerId: String,
        timestamp: Long,
        fileHash: String,
        payloadSerialized: String
    ): Pair<File, File> {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val dir = createStampsDirectory()

        val qrFile = File(dir, "${cleanBase}_${stampSuffix}_QR.png")
        FileOutputStream(qrFile).use { out ->
            qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val infoFile = File(dir, "${cleanBase}_${stampSuffix}_Stamp.txt")
        val content = buildString {
            appendLine("ReceiptShield $RECEIPT_VERSION_V4 Stamp")
            appendLine("Document: $originalFileName")
            appendLine("Issuer: $issuerId")
            appendLine("Date: ${formatter.format(Date(timestamp))}")
            appendLine("SHA-256: $fileHash")
            appendLine("Payload: $payloadSerialized")
        }
        FileOutputStream(infoFile).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }

        return Pair(qrFile, infoFile)
    }

    private fun getFileProviderUri(file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun buildOpenIntent(file: File, mimeType: String): Intent {
        val uri = getFileProviderUri(file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun buildShareIntent(file: File, mimeType: String): Intent {
        val shareDir = File(context.cacheDir, "shared_stamps")
        if (!shareDir.exists()) shareDir.mkdirs()
        val stagedFile = File(shareDir, file.name)
        file.copyTo(stagedFile, overwrite = true)

        val uri = getFileProviderUri(stagedFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "ReceiptShield ($RECEIPT_VERSION_V4) File: ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun saveToDevice(file: File, mimeType: String): String = withContext(Dispatchers.IO) {
        val isImg = mimeType.startsWith("image/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetCollection = if (isImg) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val baseFolder = if (isImg) {
                Environment.DIRECTORY_PICTURES
            } else {
                Environment.DIRECTORY_DOWNLOADS
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseFolder/ReceiptShield")
            }
            val uri = context.contentResolver.insert(targetCollection, values)
                ?: throw Exception("Cannot create storage entry")

            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: throw Exception("Cannot open output stream")
        } else {
            @Suppress("DEPRECATION")
            val rootDir = if (isImg) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            val dir = File(rootDir, "ReceiptShield")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
        }

        val folderHint = if (isImg) "Pictures/ReceiptShield" else "Downloads/ReceiptShield"
        "$folderHint/${file.name}"
    }
}
