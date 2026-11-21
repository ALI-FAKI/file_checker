package com.ali.receiptshield

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.data.StampRepository
import com.ali.receiptshield.data.StampedRecord
import com.ali.receiptshield.databinding.ActivityMainBinding
import com.ali.receiptshield.files.StampFileHelper
import com.ali.receiptshield.verification.VerificationHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securityManager: ReceiptSecurityManager
    private lateinit var repository: StampRepository
    private lateinit var fileHelper: StampFileHelper
    private lateinit var verificationHelper: VerificationHelper

    private var pendingVerifyFileUri: Uri? = null
    private var latestOutputFile: File? = null
    private var latestMimeType: String = "*/*"

    private val dateFormatter by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    private val pickFileForStamp = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processStampCreation(it) }
    }

    private val pickFileToVerify = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pendingVerifyFileUri = it
            binding.tvVerifyStatus.setTextColor(Color.parseColor("#3C4043"))
            binding.tvVerifyStatus.text =
                "Selected file: ${fileHelper.getFileName(it)}\n\nNow select the QR code image (or the stamped image containing the QR code)."
            pickQrToVerify.launch("image/*")
        }
    }

    private val pickQrToVerify = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { qrUri ->
            pendingVerifyFileUri?.let { fileUri ->
                runVerification(fileUri, qrUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Register modules
            securityManager = ReceiptSecurityManager()
            repository = StampRepository(this)
            fileHelper = StampFileHelper(this)
            verificationHelper = VerificationHelper(this, securityManager, dateFormatter)

            val currentIssuer = repository.getIssuerId()
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
            saveFile(file, latestMimeType)
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
            val entered = binding.etIssuerId.text?.toString().orEmpty()
            val finalIssuer = repository.saveIssuerId(entered)
            binding.etIssuerId.setText(finalIssuer)
            updateIssuerBadge(finalIssuer)
            binding.tvIssuerSavedMsg.text = "Saved: $finalIssuer"
            Toast.makeText(this, "Issuer ID updated", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearHistory.setOnClickListener {
            repository.clearRecords()
            renderStampedFiles()
            Toast.makeText(this, "Stamped files list cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateIssuerBadge(issuer: String) {
        binding.tvActiveIssuerBadge.text = "Active Issuer: $issuer"
    }

    private fun processStampCreation(fileUri: Uri) {
        lifecycleScope.launch {
            showLoading(true, "Hashing file...")
            binding.fileActionsRow.visibility = View.GONE
            try {
                val fileName = fileHelper.getFileName(fileUri)
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

                val issuer = repository.getIssuerId()
                val payload = securityManager.createStamp(hash, pHash, issuer)
                val qrBitmap = withContext(Dispatchers.Default) {
                    securityManager.generateQRCodeBitmap(payload.serialize())
                } ?: throw Exception("Failed to generate QR code")

                val dir = fileHelper.createStampsDirectory()
                val cleanBase = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_")
                val stampSuffix = System.currentTimeMillis()

                val outputFile: File
                val mimeType: String
                val modeNote: String

                if (isImage && decodedBitmap != null) {
                    val stampedImage = withContext(Dispatchers.Default) {
                        fileHelper.overlayQrOntoImage(decodedBitmap, qrBitmap, issuer)
                    }
                    outputFile = File(dir, "${cleanBase}_${stampSuffix}_Stamped.png")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(outputFile).use { out ->
                            stampedImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    mimeType = "image/png"
                    modeNote = "QR placed directly on image copy"
                } else {
                    val ext = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
                    outputFile = if (ext != null) {
                        File(dir, "${cleanBase}_${stampSuffix}.$ext")
                    } else {
                        File(dir, "${cleanBase}_${stampSuffix}")
                    }
                    withContext(Dispatchers.IO) {
                        FileOutputStream(outputFile).use { out -> out.write(originalBytes) }
                    }
                    mimeType = fileHelper.guessMimeType(fileUri, fileName)
                    modeNote = "Original file preserved"
                }

                repository.saveRecord(
                    StampedRecord(
                        displayName = fileName,
                        outputFilePath = outputFile.absolutePath,
                        mimeType = mimeType,
                        isImageStamp = isImage,
                        hash = hash,
                        issuerId = payload.issuerId,
                        timestamp = payload.timestamp
                    )
                )

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

    private fun runVerification(fileUri: Uri, qrUri: Uri) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvVerifyStatus.setTextColor(Color.parseColor("#3C4043"))
            binding.tvVerifyStatus.text = "Verifying document..."
            try {
                val fileName = fileHelper.getFileName(fileUri)
                val outcome = verificationHelper.verifyFileWithQr(fileUri, qrUri, fileName)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvVerifyStatus.setTextColor(verificationHelper.parseColor(outcome.colorHex))
                    binding.tvVerifyStatus.text = outcome.message
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

    private fun renderStampedFiles() {
        binding.historyContainer.removeAllViews()
        val records = repository.loadRecords()

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

            val openBtn = MaterialButton(this).apply {
                text = "Open File"
                isAllCaps = false
                textSize = 12f
                isEnabled = fileExists
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8
                }
                setOnClickListener { openFile(outFile, record.mimeType) }
            }

            val saveBtn = MaterialButton(this).apply {
                text = "Save"
                isAllCaps = false
                textSize = 12f
                isEnabled = fileExists
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8
                }
                setOnClickListener { saveFile(outFile, record.mimeType) }
            }

            val shareBtn = MaterialButton(this).apply {
                text = "Share"
                isAllCaps = false
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

    private fun openFile(file: File, mimeType: String) {
        try {
            startActivity(Intent.createChooser(fileHelper.buildOpenIntent(file, mimeType), "Open File"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app found to open this file type", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        try {
            startActivity(Intent.createChooser(fileHelper.buildShareIntent(file, mimeType), "Share File"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFile(file: File, mimeType: String) {
        lifecycleScope.launch {
            try {
                val savedLocation = fileHelper.saveToDevice(file, mimeType)
                Toast.makeText(this@MainActivity, "Saved to $savedLocation", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLoading(isLoading: Boolean, message: String?) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (message != null) binding.tvStatus.text = message
    }
}
