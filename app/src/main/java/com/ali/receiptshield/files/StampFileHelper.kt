package com.ali.receiptshield.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

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

    fun createStampsDirectory(): File {
        val dir = File(context.filesDir, "stamps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun overlayQrOntoImage(source: Bitmap, qr: Bitmap, issuerId: String): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val minDim = min(mutable.width, mutable.height)
        val qrSize = max(120, (minDim * 0.22f).toInt())
        val pad = max(12, (qrSize * 0.08f).toInt())
        val labelHeight = max(24, (qrSize * 0.16f).toInt())

        val boxWidth = qrSize + pad * 2
        val boxHeight = qrSize + pad * 2 + labelHeight

        val left = (mutable.width - boxWidth - pad).toFloat().coerceAtLeast(0f)
        val top = (mutable.height - boxHeight - pad).toFloat().coerceAtLeast(0f)
        val right = left + boxWidth
        val bottom = top + boxHeight

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A73E8")
            style = Paint.Style.STROKE
            strokeWidth = max(2f, qrSize * 0.02f)
        }

        canvas.drawRect(left, top, right, bottom, boxPaint)
        canvas.drawRect(left, top, right, bottom, borderPaint)

        val scaledQr = Bitmap.createScaledBitmap(qr, qrSize, qrSize, false)
        canvas.drawBitmap(scaledQr, left + pad, top + pad, null)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1C1E")
            textSize = max(14f, labelHeight * 0.65f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val shortIssuer = if (issuerId.length > 18) issuerId.take(18) else issuerId
        canvas.drawText(shortIssuer, left + pad, bottom - pad * 0.5f, textPaint)

        return mutable
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
            putExtra(Intent.EXTRA_TEXT, "ReceiptShield File: ${file.name}")
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
