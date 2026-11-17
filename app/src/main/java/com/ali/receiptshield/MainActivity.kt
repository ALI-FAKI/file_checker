package com.ali.receiptshield

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ali.receiptshield.crypto.ReceiptPayload
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.databinding.ActivityMainBinding
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_NONE = 0
        private const val ACTION_STAMP = 1
        private const val ACTION_VERIFY = 2
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var securityManager: ReceiptSecurityManager
    private var selectedAction = ACTION_NONE

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val file = fileFromUri(uri)
            if (file != null) {
                when (selectedAction) {
                    ACTION_STAMP -> processAndStampFile(file)
                    ACTION_VERIFY -> processAndVerifyFile(file)
                    else -> Toast.makeText(this, "Please select an action first", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Failed to read selected file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = ReceiptSecurityManager(this)

        binding.btnStamp.setOnClickListener {
            selectedAction = ACTION_STAMP
            binding.ivQrPreview.visibility = View.GONE
            pickImageLauncher.launch("image/*")
        }

        binding.btnVerify.setOnClickListener {
            selectedAction = ACTION_VERIFY
            binding.ivQrPreview.visibility = View.GONE
            pickImageLauncher.launch("image/*")
        }
    }

    private fun fileFromUri(uri: android.net.Uri): File? {
        return try {
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
        Toast.makeText(this, "Generating offline cryptographic stamp...", Toast.LENGTH_SHORT).show()

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

    private fun processAndVerifyFile(file: File) {
        Toast.makeText(this, "Verifying document offline...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val qrPayloadString = extractQRCodeFromFile(file)

            if (qrPayloadString == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "❌ Failed to find a valid QR stamp in this image", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val payload = ReceiptPayload.deserialize(qrPayloadString)
            if (payload == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "❌ Invalid QR format: Unrecognized stamp", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val isValid = securityManager.verifyReceipt(file, payload)

            withContext(Dispatchers.Main) {
                if (isValid) {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ VERIFIED AUTHENTIC! Issued by: ${payload.issuerId}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "❌ WARNING: Document has been modified or forged!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun extractQRCodeFromFile(file: File): String? {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = QRCodeReader()
            val result = reader.decode(binaryBitmap)
            result.text
        } catch (e: Exception) {
            null
        }
    }
}
