
package com.ali.receiptshield

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ali.receiptshield.crypto.ReceiptPayload
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.data.StampRepository
import com.ali.receiptshield.data.StampedRecord
import com.ali.receiptshield.databinding.ActivityMainBinding
import com.ali.receiptshield.files.DocumentStampHelper
import com.ali.receiptshield.files.DocxSignatureProcessor
import com.ali.receiptshield.files.StampFileHelper
import com.ali.receiptshield.signature.DocumentSignature
import com.ali.receiptshield.signature.SignatureEditorActivity
import com.ali.receiptshield.signature.SignaturePlacement
import com.ali.receiptshield.signature.SignaturePlacementEditorActivity
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
    private lateinit var documentStampHelper: DocumentStampHelper
    private lateinit var verificationHelper: VerificationHelper

    private var latestOutputFile: File? = null
    private var latestMimeType: String = "*/*"

    // Pending signature workflow data.
    private var pendingStampUri: Uri? = null
    private var pendingSignatureFile: File? = null
    private var pendingStampExtension: String = ""

    private val dateFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    // Select a file to stamp.
    private val pickFileForStamp =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { promptStampOptions(it) }
        }

    // Select a stamped file to verify.
    private val pickFileToVerify =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { runVerification(it) }
        }

    // Camera launcher for QR scanning.
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                processCameraScan(bitmap)
            } else {
                Toast.makeText(
                    this,
                    "Camera cancelled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    // Launch signature creation.
    private val signatureEditorLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK) {
                clearPendingSignatureWorkflow()
                Toast.makeText(
                    this,
                    "Signature creation cancelled",
                    Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }

            val signaturePath = result.data?.getStringExtra(
                SignatureEditorActivity.EXTRA_SIGNATURE_PATH
            )

            if (signaturePath.isNullOrBlank()) {
                clearPendingSignatureWorkflow()
                Toast.makeText(
                    this,
                    "Signature file was not returned",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }

            val signatureFile = File(signaturePath)

            if (!signatureFile.exists() || signatureFile.length() == 0L) {
                clearPendingSignatureWorkflow()
                Toast.makeText(
                    this,
                    "Signature image is unavailable",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }

            pendingSignatureFile = signatureFile

            val documentUri = pendingStampUri

            if (documentUri == null) {
                clearPendingSignatureWorkflow()
                return@registerForActivityResult
            }

            when (pendingStampExtension) {

                "pdf" -> {
                    launchSignaturePlacementEditor(
                        documentUri,
                        signatureFile
                    )
                }

                "docx" -> {
                    val signature = DocumentSignature(
                        signatureFile = signatureFile
                    )

                    processStampCreation(
                        documentUri,
                        signature
                    )

                    clearPendingSignatureWorkflow()
                }

                else -> {
                    clearPendingSignatureWorkflow()

                    Toast.makeText(
                        this,
                        "Signatures are currently supported for PDF and DOCX files",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    // Receive signature placement from the PDF placement editor.
    private val signaturePlacementLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK) {
                clearPendingSignatureWorkflow()

                Toast.makeText(
                    this,
                    "Signature positioning cancelled",
                    Toast.LENGTH_SHORT
                ).show()

                return@registerForActivityResult
            }

            val documentUri = pendingStampUri
            val signatureFile = pendingSignatureFile

            if (documentUri == null || signatureFile == null) {
                clearPendingSignatureWorkflow()

                Toast.makeText(
                    this,
                    "Document or signature is missing",
                    Toast.LENGTH_LONG
                ).show()

                return@registerForActivityResult
            }

            val data = result.data

            if (data == null) {
                clearPendingSignatureWorkflow()
                return@registerForActivityResult
            }

            val placement = SignaturePlacement(
                pageIndex = data.getIntExtra(
                    SignaturePlacementEditorActivity.EXTRA_PAGE_INDEX,
                    0
                ),
                x = data.getFloatExtra(
                    SignaturePlacementEditorActivity.EXTRA_X,
                    0.12f
                ),
                y = data.getFloatExtra(
                    SignaturePlacementEditorActivity.EXTRA_Y,
                    0.78f
                ),
                width = data.getFloatExtra(
                    SignaturePlacementEditorActivity.EXTRA_WIDTH,
                    0.32f
                )
            ).normalized()

            val signature = DocumentSignature(
                signatureFile = signatureFile,
                placement = placement
            )

            processStampCreation(
                documentUri,
                signature
            )

            clearPendingSignatureWorkflow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            securityManager = ReceiptSecurityManager()
            repository = StampRepository(this)
            fileHelper = StampFileHelper(this)

            documentStampHelper = DocumentStampHelper(
                this,
                securityManager
            )

            verificationHelper = VerificationHelper(
                this,
                securityManager,
                dateFormatter,
                documentStampHelper
            )

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

                text =
                    "Launch Error:\n${t::class.java.name}: ${t.message}\n\n" +
                    t.stackTrace.take(8).joinToString("\n")
            }

            setContentView(errorView)
        }
    }

    // ---------------------------------------------------------
    // SIGNATURE WORKFLOW
    // ---------------------------------------------------------

    private fun promptStampOptions(uri: Uri) {

        try {
            val fileName = fileHelper.getFileName(uri)

            val extension = fileName
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)

            if (extension != "pdf" && extension != "docx") {
                processStampCreation(uri)
                return
            }

            pendingStampUri = uri
            pendingStampExtension = extension
            pendingSignatureFile = null

            AlertDialog.Builder(this)
                .setTitle("Choose stamping method")
                .setMessage(
                    "Would you like to add a handwritten or typed signature " +
                    "along with your ReceiptShield QR code?"
                )
                .setPositiveButton("QR + Signature") { _, _ ->
                    launchSignatureEditor()
                }
                .setNegativeButton("QR Only") { _, _ ->
                    clearPendingSignatureWorkflow()
                    processStampCreation(uri)
                }
                .setNeutralButton("Cancel") { _, _ ->
                    clearPendingSignatureWorkflow()
                }
                .show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to read selected file: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun launchSignatureEditor() {
        val intent = Intent(
            this,
            SignatureEditorActivity::class.java
        )

        signatureEditorLauncher.launch(intent)
    }

    private fun launchSignaturePlacementEditor(
        documentUri: Uri,
        signatureFile: File
    ) {
        val intent = Intent(
            this,
            SignaturePlacementEditorActivity::class.java
        ).apply {

            putExtra(
                SignaturePlacementEditorActivity.EXTRA_DOCUMENT_URI,
                documentUri
            )

            putExtra(
                SignaturePlacementEditorActivity.EXTRA_SIGNATURE_PATH,
                signatureFile.absolutePath
            )
        }

        signaturePlacementLauncher.launch(intent)
    }

    private fun clearPendingSignatureWorkflow() {
        pendingStampUri = null
        pendingSignatureFile = null
        pendingStampExtension = ""
    }

    // ---------------------------------------------------------
    // NAVIGATION
    // ---------------------------------------------------------

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

        binding.sectionStamp.visibility =
            if (section == "stamp") View.VISIBLE else View.GONE

        binding.sectionVerify.visibility =
            if (section == "verify") View.VISIBLE else View.GONE

        binding.sectionHistory.visibility =
            if (section == "history") View.VISIBLE else View.GONE

        binding.sectionSettings.visibility =
            if (section == "settings") View.VISIBLE else View.GONE

        binding.scrollContent.scrollTo(0, 0)
    }

    // ---------------------------------------------------------
    // BUTTON ACTIONS
    // ---------------------------------------------------------

    private fun setupActions() {

        binding.btnCreateStamp.setOnClickListener {
            pickFileForStamp.launch("*/*")
        }

        binding.btnVerifyDoc.setOnClickListener {
            pickFileToVerify.launch("*/*")
        }

        binding.btnScanQrCamera.setOnClickListener {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    100
                )
            } else {
                takePictureLauncher.launch(null)
            }
        }

        binding.btnSaveStampedFile.setOnClickListener {

            val file = latestOutputFile

            if (file == null || !file.exists()) {
                Toast.makeText(
                    this,
                    "Create a stamped file first",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            saveFile(file, latestMimeType)
        }

        binding.btnShareStampedFile.setOnClickListener {

            val file = latestOutputFile

            if (file == null || !file.exists()) {
                Toast.makeText(
                    this,
                    "Create a stamped file first",
                    Toast.LENGTH_SHORT
                ).show()

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

            Toast.makeText(
                this,
                "Issuer ID updated",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnClearHistory.setOnClickListener {

            repository.clearRecords()
            renderStampedFiles()

            Toast.makeText(
                this,
                "Stamped files list cleared",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateIssuerBadge(issuer: String) {
        binding.tvActiveIssuerBadge.text = "Active Issuer: $issuer"
    }

    // ---------------------------------------------------------
    // CREATE STAMP
    // ---------------------------------------------------------

    private fun processStampCreation(
        fileUri: Uri,
        documentSignature: DocumentSignature? = null
    ) {

        lifecycleScope.launch {

            showLoading(true, "Preparing file...")
            binding.fileActionsRow.visibility = View.GONE

            var decodedBitmap: Bitmap? = null
            var qrBitmap: Bitmap? = null

            try {

                val fileName = fileHelper.getFileName(fileUri)
                val mime = fileHelper.guessMimeType(fileUri, fileName)

                val originalBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(fileUri)?.use {
                        it.readBytes()
                    } ?: throw Exception("Cannot read selected file")
                }

                if (originalBytes.isEmpty()) {
                    throw Exception("The selected file is empty")
                }

                val extension = fileName
                    .substringAfterLast('.', "")
                    .lowercase(Locale.ROOT)

                decodedBitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(
                        originalBytes,
                        0,
                        originalBytes.size
                    )
                }

                val isImage = decodedBitmap != null

                if (!isImage && extension !in listOf("pdf", "docx")) {
                    throw Exception(
                        when (extension) {
                            "doc" ->
                                "Legacy DOC files are not supported. Save the file as DOCX first."

                            else ->
                                "Unsupported file format. Supported formats are PDF, DOCX and images."
                        }
                    )
                }

                if (documentSignature != null) {
                    if (extension !in listOf("pdf", "docx")) {
                        throw Exception(
                            "Signatures are supported only for PDF and DOCX files"
                        )
                    }

                    if (!documentSignature.isReadyForProcessing()) {
                        throw Exception(
                            "The signature image is invalid or unavailable"
                        )
                    }
                }

                val hash = withContext(Dispatchers.Default) {
                    securityManager.hashInputStream(
                        originalBytes.inputStream()
                    )
                }

                val pHash = if (isImage && decodedBitmap != null) {
                    withContext(Dispatchers.Default) {
                        securityManager.computePerceptualHash(
                            decodedBitmap!!
                        )
                    }
                } else {
                    ""
                }

                val issuer = repository.getIssuerId()

                val payload = securityManager.createStamp(
                    hash,
                    pHash,
                    issuer
                )

                val serialized = payload.serialize()
                val timestamp = payload.timestamp

                val dir = fileHelper.createStampsDirectory()

                val cleanBase = fileName
                    .substringBeforeLast('.')
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")

                val outputFile: File
                val outputMime: String
                val modeNote: String
                val isDocumentStamp: Boolean

                showLoading(true, "Generating QR code...")

                qrBitmap = withContext(Dispatchers.Default) {
                    securityManager.generateQRCodeBitmap(serialized)
                } ?: throw Exception("Failed to generate QR code")

                // -------------------------------------------------
                // PDF
                // -------------------------------------------------

                if (extension == "pdf") {

                    outputFile = File(
                        dir,
                        "${cleanBase}_${timestamp}_Stamped.pdf"
                    )

                    withContext(Dispatchers.IO) {

                        if (documentSignature != null) {

                            documentStampHelper.stampPdfWithSignature(
                                originalBytes,
                                qrBitmap!!,
                                documentSignature,
                                outputFile
                            )

                        } else {

                            documentStampHelper.stampPdf(
                                originalBytes,
                                qrBitmap!!,
                                outputFile
                            )
                        }
                    }

                    outputMime = "application/pdf"

                    modeNote =
                        if (documentSignature != null) {
                            "ReceiptShield QR and signature embedded in PDF"
                        } else {
                            "ReceiptShield QR embedded in PDF"
                        }

                    isDocumentStamp = true

                // -------------------------------------------------
                // DOCX
                // -------------------------------------------------

                } else if (extension == "docx") {

                    outputFile = File(
                        dir,
                        "${cleanBase}_${timestamp}_Stamped.docx"
                    )

                    withContext(Dispatchers.IO) {

                        if (documentSignature != null) {

                            DocxSignatureProcessor.stampDocxWithSignature(
                                originalBytes,
                                documentSignature,
                                outputFile
                            )

                            // Add QR to the DOCX as well.
                            val signatureStampedBytes =
                                outputFile.readBytes()

                            documentStampHelper.stampDocx(
                                signatureStampedBytes,
                                qrBitmap!!,
                                outputFile
                            )

                        } else {

                            documentStampHelper.stampDocx(
                                originalBytes,
                                qrBitmap!!,
                                outputFile
                            )
                        }
                    }

                    outputMime =
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

                    modeNote =
                        if (documentSignature != null) {
                            "ReceiptShield QR and signature embedded in DOCX"
                        } else {
                            "ReceiptShield QR embedded in DOCX"
                        }

                    isDocumentStamp = true

                // -------------------------------------------------
                // IMAGE
                // -------------------------------------------------

                } else {

                    if (documentSignature != null) {
                        throw Exception(
                            "Signature stamping is not available for images yet"
                        )
                    }

                    val sourceBitmap = decodedBitmap
                        ?: throw Exception("Unable to decode image")

                    outputFile = File(
                        dir,
                        "${cleanBase}_${timestamp}_Stamped.png"
                    )

                    val stampedBitmap = withContext(Dispatchers.Default) {
                        overlayQrOnImage(
                            sourceBitmap,
                            qrBitmap!!
                        )
                    }

                    try {
                        withContext(Dispatchers.IO) {
                            FileOutputStream(outputFile).use { output ->

                                stampedBitmap.compress(
                                    Bitmap.CompressFormat.PNG,
                                    100,
                                    output
                                )
                            }
                        }
                    } finally {
                        stampedBitmap.recycle()
                    }

                    outputMime = "image/png"
                    modeNote = "ReceiptShield QR embedded in image"
                    isDocumentStamp = false
                }

                // Save stamp history.
                repository.saveRecord(
                    StampedRecord(
                        displayName = fileName,
                        outputFilePath = outputFile.absolutePath,
                        mimeType = outputMime,
                        isDocumentStamp = isDocumentStamp,
                        hash = hash,
                        issuerId = payload.issuerId,
                        timestamp = payload.timestamp,
                        payloadSerialized = serialized
                    )
                )

                latestOutputFile = outputFile
                latestMimeType = outputMime

                binding.fileActionsRow.visibility = View.VISIBLE

                binding.tvStatus.setTextColor(
                    Color.parseColor("#0F9D58")
                )

                binding.tvStatus.text =
                    "Stamp Processed!\n\n" +
                    "File: $fileName\n" +
                    "Output: ${outputFile.name}\n" +
                    "Mode: $modeNote\n" +
                    "Issuer: ${payload.issuerId}\n" +
                    "Date: ${dateFormatter.format(Date(payload.timestamp))}\n" +
                    "SHA-256: ${hash.take(24)}..."

                renderStampedFiles()
                showLoading(false, null)

            } catch (e: Exception) {

                binding.tvStatus.setTextColor(Color.RED)

                showLoading(
                    false,
                    "Error: ${e.message ?: "Unable to stamp file"}"
                )

            } finally {

                decodedBitmap?.recycle()
                qrBitmap?.recycle()
            }
        }
    }

    // ---------------------------------------------------------
    // ADD QR CODE TO IMAGE
    // ---------------------------------------------------------

    private fun overlayQrOnImage(
        original: Bitmap,
        qr: Bitmap
    ): Bitmap {

        val result = original.copy(
            Bitmap.Config.ARGB_8888,
            true
        ) ?: throw Exception("Unable to prepare image")

        val canvas = Canvas(result)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }

        val qrSize =
            (result.width.coerceAtMost(result.height) * 0.15f)
                .coerceAtLeast(80f)
                .coerceAtMost(250f)

        val margin =
            (result.width.coerceAtMost(result.height) * 0.03f)
                .coerceAtLeast(12f)

        val left = result.width - qrSize - margin
        val top = result.height - qrSize - margin

        val backgroundRect = RectF(
            left - 10f,
            top - 10f,
            left + qrSize + 10f,
            top + qrSize + 10f
        )

        paint.color = Color.WHITE

        canvas.drawRoundRect(
            backgroundRect,
            8f,
            8f,
            paint
        )

        val destination = RectF(
            left,
            top,
            left + qrSize,
            top + qrSize
        )

        canvas.drawBitmap(
            qr,
            null,
            destination,
            paint
        )

        return result
    }

    // ---------------------------------------------------------
    // VERIFY SELECTED FILE
    // ---------------------------------------------------------

    private fun runVerification(fileUri: Uri) {

        lifecycleScope.launch {

            binding.progressBar.visibility = View.VISIBLE

            binding.tvVerifyStatus.setTextColor(
                Color.parseColor("#3C4043")
            )

            binding.tvVerifyStatus.text =
                "Reading QR code and checking file..."

            try {

                val fileName = fileHelper.getFileName(fileUri)

                val outcome = verificationHelper.verifyStampedFile(
                    fileUri,
                    fileName
                )

                binding.progressBar.visibility = View.GONE
                binding.tvVerifyStatus.setTextColor(outcome.color)

                binding.tvVerifyStatus.text =
                    "${outcome.title}\n\n${outcome.message}"

            } catch (e: Exception) {

                binding.progressBar.visibility = View.GONE
                binding.tvVerifyStatus.setTextColor(Color.RED)

                binding.tvVerifyStatus.text =
                    "Verification Failed:\n${e.message}"
            }
        }
    }

    // ---------------------------------------------------------
    // PROCESS CAMERA SCAN
    // ---------------------------------------------------------

    private fun processCameraScan(bitmap: Bitmap) {

        lifecycleScope.launch {

            binding.progressBar.visibility = View.VISIBLE

            binding.tvVerifyStatus.setTextColor(
                Color.parseColor("#3C4043")
            )

            binding.tvVerifyStatus.text =
                "Scanning QR code from camera..."

            try {

                val payloadString = withContext(Dispatchers.Default) {
                    securityManager.decodeQRCode(bitmap)
                }

                binding.progressBar.visibility = View.GONE

                if (payloadString.isNullOrBlank()) {

                    binding.tvVerifyStatus.setTextColor(Color.RED)

                    binding.tvVerifyStatus.text =
                        "❌ No ReceiptShield QR code found in the image."

                    return@launch
                }

                val payload = ReceiptPayload.deserialize(payloadString)

                if (payload == null) {

                    binding.tvVerifyStatus.setTextColor(Color.RED)

                    binding.tvVerifyStatus.text =
                        "❌ Invalid QR format. This is not a ReceiptShield stamp."

                    return@launch
                }

                binding.tvVerifyStatus.setTextColor(
                    Color.parseColor("#0F9D58")
                )

                binding.tvVerifyStatus.text = """
                    ✅ VALID STAMP DETECTED
                    
                    Issuer ID: ${payload.issuerId}
                    Issued: ${dateFormatter.format(Date(payload.timestamp))}
                    
                    Note: Camera scans verify the Issuer and Date. To verify file integrity, please use the "Pick File to Verify" button.
                """.trimIndent()

            } catch (e: Exception) {

                binding.progressBar.visibility = View.GONE
                binding.tvVerifyStatus.setTextColor(Color.RED)

                binding.tvVerifyStatus.text =
                    "Camera scan failed: ${e.message}"

            } finally {
                bitmap.recycle()
            }
        }
    }

    // ---------------------------------------------------------
    // HISTORY
    // ---------------------------------------------------------

    private fun renderStampedFiles() {

        binding.historyContainer.removeAllViews()

        val records = repository.loadRecords()

        if (records.isEmpty()) {

            val emptyView = TextView(this).apply {

                text =
                    "No stamped files yet. Pick a document or file on the Stamp tab to see it here."

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

            val fileExists =
                record.outputFilePath.isNotEmpty() && outFile.exists()

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

            val typeLabel =
                if (record.isDocumentStamp) {
                    "Document Stamp (QR embedded)"
                } else {
                    "Image Stamp (QR embedded)"
                }

            val metaTv = TextView(this).apply {

                text =
                    "$typeLabel • ${if (fileExists) outFile.name else "Unavailable"}\n" +
                    "Issuer: ${record.issuerId} • " +
                    "${dateFormatter.format(Date(record.timestamp))}\n" +
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

                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = 8
                }

                setOnClickListener {
                    openFile(outFile, record.mimeType)
                }
            }

            val saveBtn = MaterialButton(this).apply {

                text = "Save"
                isAllCaps = false
                textSize = 12f
                isEnabled = fileExists

                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = 8
                }

                setOnClickListener {
                    saveFile(outFile, record.mimeType)
                }
            }

            val shareBtn = MaterialButton(this).apply {

                text = "Share"
                isAllCaps = false
                textSize = 12f
                isEnabled = fileExists

                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )

                setOnClickListener {
                    shareFile(outFile, record.mimeType)
                }
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

    // ---------------------------------------------------------
    // OPEN FILE
    // ---------------------------------------------------------

    private fun openFile(
        file: File,
        mimeType: String
    ) {

        try {

            startActivity(
                Intent.createChooser(
                    fileHelper.buildOpenIntent(file, mimeType),
                    "Open File"
                )
            )

        } catch (_: Exception) {

            Toast.makeText(
                this,
                "No app found to open this file type",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------------------------------------------------------
    // SHARE FILE
    // ---------------------------------------------------------

    private fun shareFile(
        file: File,
        mimeType: String
    ) {

        try {

            startActivity(
                Intent.createChooser(
                    fileHelper.buildShareIntent(file, mimeType),
                    "Share File"
                )
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Share failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------------------------------------------------------
    // SAVE FILE
    // ---------------------------------------------------------

    private fun saveFile(
        file: File,
        mimeType: String
    ) {

        lifecycleScope.launch {

            try {

                val savedLocation = withContext(Dispatchers.IO) {
                    fileHelper.saveToDevice(file, mimeType)
                }

                Toast.makeText(
                    this@MainActivity,
                    "Saved to $savedLocation",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    this@MainActivity,
                    "Save failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------------------------------------------------------
    // LOADING
    // ---------------------------------------------------------

    private fun showLoading(
        isLoading: Boolean,
        message: String?
    ) {

        binding.progressBar.visibility =
            if (isLoading) View.VISIBLE else View.GONE

        if (message != null) {
            binding.tvStatus.text = message
        }
    }
}
