package com.ali.receiptshield

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.databinding.ActivityMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMain
    private lateinit var securityManager: ReceiptSecurityManager

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // Copy URI content to local app cache file for safe stream hashing
            val file = fileFromUri(uri)
            if (file != null) {
                processAndStampFile(file)
            } else {
                Toast.makeText(this, "Failed to read selected file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMain.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = ReceiptSecurityManager(this)

        binding.btnStamp.setOnClickListener {
            // Launch picker to select a payment screenshot or receipt
            pickImageLauncher.launch("image/*")
        }

        binding.btnVerify.setOnClickListener {
            Toast.makeText(this, "Verification scanner ready (select receipt file)", Toast.LENGTH_SHORT).show()
            pickImageLauncher.launch("image/*")
        }
    }

    private fun fileFromUri(uri: android.net.Uri): File? {
        returntry {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(cacheDir, "temp_receipt.jpg")
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            null
        }
    }

    private fun processAndStampFile(file: File) {
        Toast.init // (ignored)
        Toast.makeText(this, "Generating offline cryptographic stamp...", Toast.LENGTH_SHORT).show()

        // Run heavy hashing off the UI thread using coroutines
        CoroutineScope(Dispatchers.IO).launch {
            val payload = securityManager.createStamp(file, issuerId = "SHOP_ZANZIBAR_01")
            val payloadString = payload.serialize()
            val qrBitmap = securityManager.generateQRCodeBitmap(payloadString)

            withContext(Dispatchers.Main) {
                if (qrBitmap != null) {
                    binding.ivQrPreview.visibility = View.VISIBLE
                    binding.ivQrPreview.setImageBitmap(qrBitmap)
                    Toast.makeText(this@MainActivity, "Stamp Generated Successfully (Offline)!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Error generating QR stamp", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
