package com.ali.receiptshield

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ali.receiptshield.crypto.ReceiptPayload
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securityManager: ReceiptSecurityManager
    private var pendingVerifyFileUri: Uri? = null

    private val pickFileForStamp = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processStampCreation(it) }
    }

    private val pickFileToVerify = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pendingVerifyFileUri = it
            binding.tvStatus.text = "File selected: ${getFileName(it)}\n\nNow select the QR Code image."
            binding.tvStatus.setTextColor(Color.parseColor("#3C4043"))
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = ReceiptSecurityManager()

        binding.btnCreateStamp.setOnClickListener { pickFileForStamp.launch("*/*") }
        binding.btnVerifyDoc.setOnClickListener { pickFileToVerify.launch("*/*") }
    }

    private fun processStampCreation(fileUri: Uri) {
        lifecycleScope.launch {
            showLoading(true, "Hashing file...")
            try {
                val fileName = getFileName(fileUri)
                val inputStream = contentResolver.openInputStream(fileUri)
                val hash = withContext(Dispatchers.IO) {
                    securityManager.hashInputStream(inputStream ?: throw Exception("Cannot open file"))
                }
                inputStream?.close()

                // Try to generate a perceptual hash (only works for images)
                val pHash = withContext(Dispatchers.IO) {
                    try {
                        val bmp = contentResolver.openInputStream(fileUri)?.use { BitmapFactory.decodeStream(it) }
                        if (bmp != null) securityManager.computePerceptualHash(bmp) else ""
                    } catch (e: Exception) { "" }
                }

                val payload = securityManager.createStamp(hash, pHash, "SHOP_ZANZIBAR_01")
                val qrBitmap = withContext(Dispatchers.Default) {
                    securityManager.generateQRCodeBitmap(payload.serialize())
                }

                withContext(Dispatchers.Main) {
                    binding.ivQrPreview.setImageBitmap(qrBitmap)
                    binding.ivQrPreview.visibility = View.VISIBLE
                    binding.tvStatus.setTextColor(Color.parseColor("#0F9D58"))
                    binding.tvStatus.text = "✅ Stamp Created!\n\nFile: $fileName\n\nSHA-256: ${hash.take(16)}...\nIssuer: ${payload.issuerId}\nDate: ${Date(payload.timestamp)}"
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
            showLoading(true, "Verifying Document...")
            try {
                val fileName = getFileName(fileUri)

                // 1. Decode QR
                val qrBitmap = contentResolver.openInputStream(qrUri)?.use { BitmapFactory.decodeStream(it) }
                val payloadString = withContext(Dispatchers.Default) {
                    securityManager.decodeQRCode(qrBitmap ?: throw Exception("Cannot read QR Image"))
                } ?: throw Exception("No valid QR code found.")

                val payload = ReceiptPayload.deserialize(payloadString)
                    ?: throw Exception("Invalid ReceiptShield payload format.")

                // 2. Compute hashes of the live file
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

                // 3. Verification Logic
                withContext(Dispatchers.Main) {
                    binding.ivQrPreview.visibility = View.GONE

                    if (liveHash.equals(payload.fileHash, ignoreCase = true)) {
                        // Perfect match
                        binding.tvStatus.setTextColor(Color.parseColor("#0F9D58")) // Green
                        binding.tvStatus.text = "✅ 100% AUTHENTIC\n\nFile: $fileName\n\nThe file is unmodified.\n\nIssuer: ${payload.issuerId}\nDate: ${Date(payload.timestamp)}"
                    } else if (payload.perceptualHash.isNotEmpty() && securityManager.isVisuallySimilar(livePHash, payload.perceptualHash)) {
                        // Visual match, but binary changed (WhatsApp compression)
                        binding.tvStatus.setTextColor(Color.parseColor("#F59E0B")) // Amber/Orange
                        binding.tvStatus.text = "⚠️ AUTHENTIC (Re-compressed)\n\nFile: $fileName\n\nThe visual content matches the original, but the file was compressed/re-encoded (e.g., sent via WhatsApp).\n\nIssuer: ${payload.issuerId}\nDate: ${Date(payload.timestamp)}"
                    } else {
                        // Tampered
                        binding.tvStatus.setTextColor(Color.RED)
                        binding.tvStatus.text = "❌ TAMPERED / FORGED\n\nFile: $fileName\n\nThe visual content or binary has been altered. This document is not authentic.\n\nExpected: ${payload.fileHash.take(16)}...\nActual: ${liveHash.take(16)}..."
                    }
                    showLoading(false, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.setTextColor(Color.RED)
                    showLoading(false, "Verification Failed:\n${e.message}")
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
