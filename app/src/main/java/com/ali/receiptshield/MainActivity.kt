package com.ali.receiptshield

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ali.receiptshield.crypto.ReceiptPayload
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class StampedRecord(
    val displayName: String,
    val outputFilePath: String,
    val mimeType: String,
    val isImageStamp: Boolean,
    val hash: String,
    val issuerId: String,
    val timestamp: Long
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securityManager: ReceiptSecurityManager
    private var pendingVerifyFileUri: Uri? = null
    private var latestOutputFile: File? = null
    private var latestMimeType: String = "*/*"

    private val prefs by lazy { getSharedPreferences("receiptshield_prefs", Context.MODE_PRIVATE) }
    private val dateFormatter by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    private val pickFileForStamp = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processStampCreation(it) }
    }

    private val pickFileToVerify = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pendingVerifyFileUri = it
            binding.tvVerifyStatus.setTextColor(Color.parseColor("#3C4043"))
            binding.tvVerifyStatus.text =
                "Selected file: ${getFileName(it)}\n\nNow select the QR code image (or the stamped image containing the QR code)."
            pickQrToVerify.launch("image/*")
        }
    }

    private val pickQrToVerify = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { qrUri ->
            pendingVerifyFileUri?.let { fileUri ->
                performVerification(fileUri, qrUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            securityManager = ReceiptSecurityManager()

            val currentIssuer = getIssuerId()
            binding.etIssuerId.setText(currentIssuer)
            updateIssuerBadge(currentIssuer)

            setupNavigation()
            setupActions()
            renderStampedFiles()
        } catch (t: Throwable) {
            val errorView = TextView(this).apply {
                setPadding(48, 48, 48, 48)
                setTextColor(Color.RED)
                textSize = 14f
                text = "Launch Error:\n${t::class.java.name}: ${t.message}\n\n${t.stackTrace.take(8).joinToString("\n")}"
            }
            setContentView(errorView)
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_stamp -> {
                    showSection("stamp")
                    true
                }
                R.id.nav_verify -> {
                    showSection("verify")
                    true
                }
                R.id.nav_history -> {
                    renderStampedFiles()
                    showSection("history")
                    true
                }
                R.id.nav_settings -> {
                    binding.tvIssuerSavedMsg.text = ""
                    showSection("settings")
                    true
                }
                else -> false
            }
        }
    }

    private fun showSection(section: String) {
        binding.sectionStamp.visibility = if (section == "stamp") View.VISIBLE else View.GONE
        binding.sectionVerify.visibility = if (section == "verify") View.VISIBLE else View.GONE
        binding.sectionHistory.visibility = if (section == "history") View.VISIBLE else View.GONE
        binding.sectionSettings.visibility = if (section == "settings") View.VISIBLE else View.GONE
        binding.scrollContent.scrollTo(0, 0)
    }

    private fun setupActions() {
        binding.btnCreateStamp.setOnClickListener { pickFileForStamp.launch("*/*") }
        binding.btnVerifyDoc.setOnClickListener { pickFileToVerify.launch("*/*") }

        binding.btnSaveStampedFile.setOnClickListener {
            val file = latestOutputFile
            if (file == null || !file.exists()) {
                Toast.makeText(this, "Create a stamped file first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveFileToDevice(file, latestMimeType)
        }

        binding.btnShareStampedFile.setOnClickListener {
            val file = latestOutputFile
            if (file == null || !file.exists()) {
                Toast.makeText(this, "Create a stamped file first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareFile(file, latestMimeType)
        }

        binding.btnSaveIssuer.setOnClickListener {
            val entered = binding.etIssuerId.text?.toString()?.trim().orEmpty()
            val finalIssuer = if (entered.isEmpty()) "SHOP_ZANZIBAR_01" else entered
            prefs.edit().putString("issuer_id", finalIssuer).apply()
            binding.etIssuerId.setText(finalIssuer)
            updateIssuerBadge(finalIssuer)
            binding.tvIssuerSavedMsg.text = "Saved: $finalIssuer"
            Toast.makeText(this, "Issuer ID updated", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearHistory.setOnClickListener {
            prefs.edit().remove("stamped_records").apply()
            renderStampedFiles()
            Toast.makeText(this, "Stamped files list cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getIssuerId(): String =
        prefs.getString("issuer_id", "SHOP_ZANZIBAR_01") ?: "SHOP_ZANZIBAR_01"

    private fun updateIssuerBadge(issuer: String) {
        binding.tvActiveIssuerBadge.text = "Active Issuer: $issuer"
    }

    private fun processStampCreation(fileUri: Uri) {
        lifecycleScope.launch {
            showLoading(true, "Hashing file...")
            binding.fileActionsRow.visibility = View.GONE
            try {
                val fileName = getFileName(fileUri)
                val originalBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read selected file")
                }

                val hash = withContext(Dispatchers.Default) {
                    securityManager.hashInputStream(originalBytes.inputStream())
                }

                val decodedBitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                }

                val isImage = decodedBitmap != null
                val pHash = if (decodedBitmap != null) {
                    withContext(Dispatchers.Default) {
                        securityManager.computePerceptualHash(decodedBitmap)
                    }
                } else ""

                val issuer = getIssuerId()
                val payload = securityManager.createStamp(hash, pHash, issuer)
                val serialized = payload.serialize()
                val qrBitmap = withContext(Dispatchers.Default) {
                    securityManager.generateQRCodeBitmap(serialized)
                } ?: throw Exception("Failed to generate QR code")

                val dir = File(filesDir, "stamps")
                if (!dir.exists()) dir.mkdirs()

                val cleanBase = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_")
                val stampSuffix = System.currentTimeMillis()

                val outputFile: File
                val mimeType: String
                val modeNote: String

                if (isImage && decodedBitmap != null) {
                    // Put QR code directly onto a copy of the image
                    val stampedImage = withContext(Dispatchers.Default) {
                        overlayQrOntoImage(decodedBitmap, qrBitmap, issuer)
                    }
                    outputFile = File(dir, "${cleanBase}_${stampSuffix}_Stamped.png")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(outputFile).use { out ->
                            stampedImage.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    mimeType = "image/png"
                    modeNote = "QR placed directly on image copy"
                } else {
                    // Non-image file: keep original file untouched (no zip)
                    val ext = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
                    outputFile = if (ext != null) {
                        File(dir, "${cleanBase}_${stampSuffix}.$ext")
                    } else {
                        File(dir, "${cleanBase}_${stampSuffix}")
                    }
                    withContext(Dispatchers.IO) {
                        FileOutputStream(outputFile).use { out -> out.write(originalBytes) }
                    }
                    mimeType = guessMimeType(fileUri, fileName)
                    modeNote = "Original file preserved (no archive)"
                }

                val record = StampedRecord(
                    displayName = fileName,
                    outputFilePath = outputFile.absolutePath,
                    mimeType = mimeType,
                    isImageStamp = isImage,
                    hash = hash,
                    issuerId = payload.issuerId,
                    timestamp = payload.timestamp
                )
                saveRecord(record)

                withContext(Dispatchers.Main) {
                    latestOutputFile = outputFile
                    latestMimeType = mimeType
                    binding.fileActionsRow.visibility = View.VISIBLE
                    binding.tvStatus.setTextColor(Color.parseColor("#0F9D58"))
                    binding.tvStatus.text =
                        "Stamp Created!\n\n" +
                        "File: $fileName\n" +
                        "Output: ${outputFile.name}\n" +
                        "Mode: $modeNote\n" +
                        "Issuer: ${payload.issuerId}\n" +
                        "Date: ${dateFormatter.format(Date(payload.timestamp))}\n" +
                        "SHA-256: ${hash.take(24)}..."
                    showLoading(false, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.setTextColor(Color.RED)
                    showLoading(false, "Error: ${e.message}")
                }
            }
        }
    }

    private fun overlayQrOntoImage(source: Bitmap, qr: Bitmap, issuerId: String): Bitmap {
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

    private fun performVerification(fileUri: Uri, qrUri: Uri) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvVerifyStatus.setTextColor(Color.parseColor("#3C4043"))
            binding.tvVerifyStatus.text = "Verifying document..."
            try {
                val fileName = getFileName(fileUri)

                val qrBitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(qrUri)?.use { BitmapFactory.decodeStream(it) }
                }
                val payloadString = withContext(Dispatchers.Default) {
                    securityManager.decodeQRCode(qrBitmap ?: throw Exception("Cannot read QR Image"))
                } ?: throw Exception("No valid QR code found in the selected image.")

                val payload = ReceiptPayload.deserialize(payloadString)
                    ?: throw Exception("Invalid ReceiptShield payload format.")

                val liveHash = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(fileUri)?.use { securityManager.hashInputStream(it) }
                        ?: throw Exception("Cannot open file")
                }

                val livePHash = withContext(Dispatchers.IO) {
                    try {
                        val bmp = contentResolver.openInputStream(fileUri)?.use { BitmapFactory.decodeStream(it) }
                        if (bmp != null) securityManager.computePerceptualHash(bmp) else ""
                    } catch (e: Exception) { "" }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    val formattedDate = dateFormatter.format(Date(payload.timestamp))

                    if (liveHash.equals(payload.fileHash, ignoreCase = true)) {
                        binding.tvVerifyStatus.setTextColor(Color.parseColor("#0F9D58"))
                        binding.tvVerifyStatus.text =
                            "100% AUTHENTIC\n\n" +
                            "File: $fileName\n" +
                            "Status: Unmodified binary match\n" +
                            "Issuer: ${payload.issuerId}\n" +
                            "Stamped: $formattedDate"
                    } else if (payload.perceptualHash.isNotEmpty() &&
                        securityManager.isVisuallySimilar(livePHash, payload.perceptualHash)
                    ) {
                        binding.tvVerifyStatus.setTextColor(Color.parseColor("#F59E0B"))
                        binding.tvVerifyStatus.text =
                            "AUTHENTIC (Stamped Copy / Re-encoded)\n\n" +
                            "File: $fileName\n" +
                            "Status: Visual match with original image\n" +
                            "Issuer: ${payload.issuerId}\n" +
                            "Stamped: $formattedDate"
                    } else {
                        binding.tvVerifyStatus.setTextColor(Color.RED)
                        binding.tvVerifyStatus.text =
                            "TAMPERED / FORGED\n\n" +
                            "File: $fileName\n" +
                            "Expected Hash: ${payload.fileHash.take(18)}...\n" +
                            "Actual Hash: ${liveHash.take(18)}..."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvVerifyStatus.setTextColor(Color.RED)
                    binding.tvVerifyStatus.text = "Verification Failed:\n${e.message}"
                }
            }
        }
    }

    private fun saveRecord(record: StampedRecord) {
        val currentList = loadRecords().toMutableList()
        currentList.add(0, record)
        val trimmed = currentList.take(50)
        val array = JSONArray()
        for (item in trimmed) {
            array.put(JSONObject().apply {
                put("displayName", item.displayName)
                put("outputFilePath", item.outputFilePath)
                put("mimeType", item.mimeType)
                put("isImageStamp", item.isImageStamp)
                put("hash", item.hash)
                put("issuerId", item.issuerId)
                put("timestamp", item.timestamp)
            })
        }
        prefs.edit().putString("stamped_records", array.toString()).apply()
    }

    private fun loadRecords(): List<StampedRecord> {
        val raw = prefs.getString("stamped_records", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<StampedRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    StampedRecord(
                        displayName = obj.optString("displayName", "Unknown"),
                        outputFilePath = obj.optString("outputFilePath", ""),
                        mimeType = obj.optString("mimeType", "*/*"),
                        isImageStamp = obj.optBoolean("isImageStamp", false),
                        hash = obj.optString("hash", ""),
                        issuerId = obj.optString("issuerId", "SHOP_ZANZIBAR_01"),
                        timestamp = obj.optLong("timestamp", 0L)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun renderStampedFiles() {
        binding.historyContainer.removeAllViews()
        val records = loadRecords()

        if (records.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No stamped files yet. Pick an image or file on the Stamp tab to see it here."
                setTextColor(Color.parseColor("#5F6368"))
                textSize = 14f
                setPadding(24, 24, 24, 24)
                setBackgroundColor(Color.WHITE)
            }
            binding.historyContainer.addView(emptyView)
            return
        }

        for (record in records) {
            val outFile = File(record.outputFilePath)
            val fileExists = record.outputFilePath.isNotEmpty() && outFile.exists()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(28, 24, 28, 24)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 20
                layoutParams = params
            }

            val titleTv = TextView(this).apply {
                text = record.displayName
                setTextColor(Color.parseColor("#1A1C1E"))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }

            val typeLabel = if (record.isImageStamp) "Stamped Image (QR on image)" else "Original File"
            val metaTv = TextView(this).apply {
                text = "$typeLabel • ${if (fileExists) outFile.name else "Unavailable"}\n" +
                    "Issuer: ${record.issuerId} • ${dateFormatter.format(Date(record.timestamp))}\n" +
                    "SHA-256: ${record.hash.take(20)}..."
                setTextColor(Color.parseColor("#5F6368"))
                textSize = 12f
                setPadding(0, 8, 0, 12)
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val openBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Open File"
                textAllCaps = false
                textSize = 12f
                isEnabled = fileExists
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8
                }
                setOnClickListener { openFile(outFile, record.mimeType) }
            }

            val saveBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Save"
                textAllCaps = false
                textSize = 12f
                isEnabled = fileExists
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8
                }
                setOnClickListener { saveFileToDevice(outFile, record.mimeType) }
            }

            val shareBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Share"
                textAllCaps = false
                textSize = 12f
                isEnabled = fileExists
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { shareFile(outFile, record.mimeType) }
            }

            actionsRow.addView(openBtn)
            actionsRow.addView(saveBtn)
            actionsRow.addView(shareBtn)

            card.addView(titleTv)
            card.addView(metaTv)
            card.addView(actionsRow)

            binding.historyContainer.addView(card)
        }
    }

    private fun guessMimeType(uri: Uri, fileName: String): String {
        contentResolver.getType(uri)?.let { return it }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "*/*"
    }

    private fun getFileProviderUri(file: File): Uri {
        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
    }

    private fun openFile(file: File, mimeType: String) {
        try {
            val uri = getFileProviderUri(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (mimeType.isBlank()) "*/*" else mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open File"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file type", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        try {
            val shareDir = File(cacheDir, "shared_stamps")
            if (!shareDir.exists()) shareDir.mkdirs()
            val stagedFile = File(shareDir, file.name)
            file.copyTo(stagedFile, overwrite = true)

            val uri = getFileProviderUri(stagedFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (mimeType.isBlank()) "*/*" else mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "ReceiptShield File: ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share File"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFileToDevice(file: File, mimeType: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var saved = false
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
                        put(MediaStore.MediaColumns.MIME_TYPE, if (mimeType.isBlank()) "application/octet-stream" else mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseFolder/ReceiptShield")
                    }
                    val uri = contentResolver.insert(targetCollection, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(file).use { input -> input.copyTo(out) }
                        }
                        saved = true
                    }
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
                    saved = true
                }

                withContext(Dispatchers.Main) {
                    if (saved) {
                        val folderHint = if (isImg) "Pictures/ReceiptShield" else "Downloads/ReceiptShield"
                        Toast.makeText(this@MainActivity, "Saved to $folderHint/${file.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Could not save file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean, message: String?) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (message != null) binding.tvStatus.text = message
    }

    private fun getFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
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
}
