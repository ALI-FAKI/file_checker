package com.ali.receiptshield

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
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
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securityManager: ReceiptSecurityManager

    // State for verification flow
    private var pendingVerifyFileUri: Uri? = null

    // 1. Pick file to generate a stamp
    private val pickFileForStamp = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processStampCreation(it) }
    }

    // 2. Pick file to verify
    private val pickFileToVerify = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pendingVerifyFileUri = it
            binding.tvStatus.text = "File selected: ${getFileName(it)}\n\nNow select the QR Code image to verify against."
            binding.tvStatus.setTextColor(Color.parseColor("#3C4043"))
            pickQrToVerify.launch("image/*")
        }
    }

    // 3. Pick the QR code image to verify against
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

        binding.btnCreateStamp.setOnClickListener {
            pickFileForStamp.launch("*/*")
        }

        binding.btnVerifyDoc.setOnClickListener {
            pickFileToVerify.launch("*/*")
        }
    }

    private fun processStampCreation(fileUri: Uri) {
        lifecycleScope.launch {
            showLoading(true, "Hashing file...")
            try {
                val fileName = getFileName(fileUri)
                val inputStream = contentResolver.openInputStream(fileUri)
                
                // Hash file
                val hash = withContext(Dispatchers.IO) {
                    securityManager.hashInputStream(inputStream ?: throw Exception("Cannot open file"))
                }
                inputStream?.close()

                showLoading(true, "Generating QR Code...")
                val payload = securityManager.createStamp(hash, "SHOP_ZANZIBAR_01")
                val payloadString = payload.serialize()

                // Generate QR
                val qrBitmap = withContext(Dispatchers.Default) {
                    securityManager.generateQRCodeBitmap(payloadString)
                }

                withContext(Dispatchers.Main) {
                    binding.ivQrPreview.setImageBitmap(qrBitmap)
                    binding.ivQrPreview.visibility = View.VISIBLE
                    binding.tvStatus.setTextColor(Color.parseColor("#0F9D58"))
                    binding.tvStatus.text = "✅ Stamp Created!\n\nFile: $fileName\n\nHash: ${hash.take(16)}...\nIssuer: ${payload.issuerId}\nDate: ${Date(payload.timestamp)}"
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

                // 1. Decode QR Image
                val qrInputStream = contentResolver.openInputStream(qrUri)
                val qrBitmap = BitmapFactory.decodeStream(qrInputStream)
                qrInputStream?.close()
                
                val payloadString = withContext(Dispatchers.Default) {
                    securityManager.decodeQRCode(qrBitmap ?: throw Exception("Cannot read QR Image"))
                } ?: throw Exception("No valid QR code found in the selected image.")

                val payload = ReceiptPayload.deserialize(payloadString)
                    ?: throw Exception("Invalid ReceiptShield payload format.")

                // 2. Hash the original document
                val fileInputStream = contentResolver.openInputStream(fileUri)
                val liveHash = withContext(Dispatchers.IO) {
                    securityManager.hashInputStream(fileInputStream ?: throw Exception("Cannot open file"))
                }
                fileInputStream?.close()

                // 3. Compare Hashes
                val isAuthentic = securityManager.verifyHash(liveHash, payload)

                withContext(Dispatchers.Main) {
                    binding.ivQrPreview.visibility = View.GONE
                    if (isAuthentic) {
                        binding.tvStatus.setTextColor(Color.parseColor("#0F9D58")) // Green
                        binding.tvStatus.text = "✅ AUTHENTIC\n\nFile: $fileName\n\nThis document matches the original stamp perfectly.\n\nIssuer: ${payload.issuerId}\nDate: ${Date(payload.timestamp)}"
                    } else {
                        binding.tvStatus.setTextColor(Color.RED)
                        binding.tvStatus.text = "❌ TAMPERED / FORGED\n\nFile: $fileName\n\nHash mismatch! This document has been modified since the stamp was created.\n\nExpected: ${payload.fileHash.take(16)}...\nActual: ${liveHash.take(16)}..."
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
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result ?: "Unknown File"
    }
}
