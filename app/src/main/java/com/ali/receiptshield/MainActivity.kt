package com.ali.receiptshield

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StampedRecord(
    val fileName: String,
    val hash: String,
    val issuerId: String,
    val timestamp: Long,
    val payloadSerialized: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securityManager: ReceiptSecurityManager
    private var pendingVerifyFileUri: Uri? = null
    private var latestQrBitmap: Bitmap? = null
    private var latestFileName: String = "stamp"

    private val prefs by lazy { getSharedPreferences("receiptshield_prefs", Context.MODE_PRIVATE) }
    private val dateFormatter by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    private val pickFileForStamp = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processStampCreation(it) }
    }

    private val pickFileToVerify = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pendingVerifyFileUri = it
            binding.tvVerifyStatus.setTextColor(Color.parseColor("#3C4043"))
            binding.tvVerifyStatus.text = "File selected: ${getFileName(it)}\n\nNow select the QR Code image."
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
            renderStampedHistory()
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
                    renderStampedHistory()
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

        binding.btnSaveQr.setOnClickListener {
            val bmp = latestQrBitmap
            if (bmp == null) {
                Toast.makeText(this, "Create a QR stamp first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveQrToGallery(bmp, latestFileName)
        }

        binding.btnShareQr.setOnClickListener {
            val bmp = latestQrBitmap
            if (bmp == null) {
                Toast.makeText(this, "Create a QR stamp first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareQrBitmap(bmp, latestFileName)
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
            renderStampedHistory()
            Toast.makeText(this, "Stamped history cleared", Toast.LENGTH_SHORT).show()
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
            binding.qrActionsRow.visibility = View.GONE
            try {
                val fileName = getFileName(fileUri)
                latestFileName = fileName

                val hash = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(fileUri)?.use { stream ->
                        securityManager.hashInputStream(stream)
                    } ?: throw Exception("Cannot open file")
                }

                val pHash = withContext(Dispatchers.IO) {
                    try {
                        val bmp = contentResolver.openInputStream(fileUri)?.use { BitmapFactory.decodeStream(it) }
                        if (bmp != null) securityManager.computePerceptualHash(bmp) else ""
                    } catch (e: Exception) { "" }
                }

                val issuer = getIssuerId()
                val payload = securityManager.createStamp(hash, pHash, issuer)
                val serialized = payload.serialize()
                val qrBitmap = withContext(Dispatchers.Default) {
                    securityManager.generateQRCodeBitmap(serialized)
                }

                val record = StampedRecord(
                    fileName = fileName,
                    hash = hash,
                    issuerId = payload.issuerId,
                    timestamp = payload.timestamp,
                    payloadSerialized = serialized
                )
                saveRecord(record)

                withContext(Dispatchers.Main) {
                    latestQrBitmap = qrBitmap
                    binding.ivQrPreview.setImageBitmap(qrBitmap)
                    binding.ivQrPreview.visibility = if (qrBitmap != null) View.VISIBLE else View.GONE
                    binding.qrActionsRow.visibility = if (qrBitmap != null) View.VISIBLE else View.GONE
                    binding.tvStatus.setTextColor(Color.parseColor("#0F9D58"))
                    binding.tvStatus.text =
                        "✅ Stamp Created!\n\n" +
                        "File: $fileName\n" +
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
                } ?: throw Exception("No valid QR code found.")

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
                            "✅ 100% AUTHENTIC\n\n" +
                            "File: $fileName\n" +
                            "Status: Unmodified binary match\n" +
                            "Issuer: ${payload.issuerId}\n" +
                            "Stamped: $formattedDate"
                    } else if (payload.perceptualHash.isNotEmpty() &&
                        securityManager.isVisuallySimilar(livePHash, payload.perceptualHash)
                    ) {
                        binding.tvVerifyStatus.setTextColor(Color.parseColor("#F59E0B"))
                        binding.tvVerifyStatus.text =
                            "⚠️ AUTHENTIC (Re-compressed)\n\n" +
                            "File: $fileName\n" +
                            "Status: Visual match, re-encoded/compressed\n" +
                            "Issuer: ${payload.issuerId}\n" +
                            "Stamped: $formattedDate"
                    } else {
                        binding.tvVerifyStatus.setTextColor(Color.RED)
                        binding.tvVerifyStatus.text =
                            "❌ TAMPERED / FORGED\n\n" +
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
                put("fileName", item.fileName)
                put("hash", item.hash)
                put("issuerId", item.issuerId)
                put("timestamp", item.timestamp)
                put("payloadSerialized", item.payloadSerialized)
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
                        fileName = obj.optString("fileName", "Unknown"),
                        hash = obj.optString("hash", ""),
                        issuerId = obj.optString("issuerId", "SHOP_ZANZIBAR_01"),
                        timestamp = obj.optLong("timestamp", 0L),
                        payloadSerialized = obj.optString("payloadSerialized", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun renderStampedHistory() {
        binding.historyContainer.removeAllViews()
        val records = loadRecords()

        if (records.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No stamped files yet. Create a stamp on the Stamp tab to see it here."
                setTextColor(Color.parseColor("#5F6368"))
                textSize = 14f
                setPadding(24, 24, 24, 24)
                setBackgroundColor(Color.WHITE)
            }
            binding.historyContainer.addView(emptyView)
            return
        }

        for (record in records) {
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
                text = record.fileName
                setTextColor(Color.parseColor("#1A1C1E"))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val metaTv = TextView(this).apply {
                text = "Issuer: ${record.issuerId} • ${dateFormatter.format(Date(record.timestamp))}\nSHA-256: ${record.hash.take(20)}... (Tap to preview QR)"
                setTextColor(Color.parseColor("#5F6368"))
                textSize = 12f
                setPadding(0, 8, 0, 0)
            }

            card.addView(titleTv)
            card.addView(metaTv)

            card.setOnClickListener {
                if (record.payloadSerialized.isNotEmpty()) {
                    val bmp = securityManager.generateQRCodeBitmap(record.payloadSerialized)
                    latestQrBitmap = bmp
                    latestFileName = record.fileName
                    binding.ivQrPreview.setImageBitmap(bmp)
                    binding.ivQrPreview.visibility = if (bmp != null) View.VISIBLE else View.GONE
                    binding.qrActionsRow.visibility = if (bmp != null) View.VISIBLE else View.GONE
                    binding.tvStatus.setTextColor(Color.parseColor("#1A73E8"))
                    binding.tvStatus.text =
                        "📄 Saved Stamp Preview\n\n" +
                        "File: ${record.fileName}\n" +
                        "Issuer: ${record.issuerId}\n" +
                        "Date: ${dateFormatter.format(Date(record.timestamp))}\n" +
                        "SHA-256: ${record.hash.take(24)}..."
                    binding.bottomNav.selectedItemId = R.id.nav_stamp
                }
            }

            binding.historyContainer.addView(card)
        }
    }

    private fun saveQrToGallery(bitmap: Bitmap, baseName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val safeName = "ReceiptShield_${baseName.substringBeforeLast('.')}_${System.currentTimeMillis()}.png"
                var savedOk = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ReceiptShield")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            savedOk = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ReceiptShield")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, safeName)
                    FileOutputStream(file).use { out ->
                        savedOk = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (savedOk) {
                        Toast.makeText(this@MainActivity, "QR saved to Pictures/ReceiptShield", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Could not save QR image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareQrBitmap(bitmap: Bitmap, baseName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val shareDir = File(cacheDir, "shared_qr")
                if (!shareDir.exists()) shareDir.mkdirs()
                val file = File(shareDir, "QR_${baseName.substringBeforeLast('.')}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                        putExtra(Intent.EXTRA_TEXT, "ReceiptShield QR Stamp for $baseName")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share QR Stamp"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
