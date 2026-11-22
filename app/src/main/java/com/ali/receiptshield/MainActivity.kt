
package com.ali.receiptshield

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import com.ali.receiptshield.files.ImageStampProcessor
import com.ali.receiptshield.files.StampConfiguration
import com.ali.receiptshield.files.StampFileHelper
import com.ali.receiptshield.files.StampMode
import com.ali.receiptshield.qr.QrPlacement
import com.ali.receiptshield.qr.QrPlacementEditorActivity
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

    private var pendingStampUri: Uri? = null
    private var pendingSignatureFile: File? = null
    private var pendingStampExtension: String = ""
    private var pendingStampMode: StampMode = StampMode.QR_ONLY
    private var pendingQrPlacement: QrPlacement = QrPlacement()
    private var pendingSignaturePlacement: SignaturePlacement =
        SignaturePlacement()

    private var pendingPayloadSerialized: String = ""
    private var pendingOriginalHash: String = ""
    private var pendingIssuer: String = ""
    private var pendingTimestamp: Long = 0L

    private val dateFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    private val pickFileForStamp =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { promptStampOptions(it) }
        }

    private val pickFileToVerify =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { runVerification(it) }
        }

    private val takePictureLauncher =
        registerForActivityResult(
            ActivityResultContracts.TakePicturePreview()
        ) { bitmap ->
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

    private val signatureEditorLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK) {
                cancelPendingWorkflow("Signature creation cancelled")
                return@registerForActivityResult
            }

            val signaturePath = result.data?.getStringExtra(
                SignatureEditorActivity.EXTRA_SIGNATURE_PATH
            )

            if (signaturePath.isNullOrBlank()) {
                cancelPendingWorkflow("Signature file was not returned")
                return@registerForActivityResult
            }

            val signatureFile = File(signaturePath)

            if (!signatureFile.exists() || signatureFile.length() == 0L) {
                cancelPendingWorkflow("Signature image is unavailable")
                return@registerForActivityResult
            }

            pendingSignatureFile = signatureFile
            continueAfterSignatureCreation()
        }

    private val signaturePlacementLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK) {
                cancelPendingWorkflow("Signature positioning cancelled")
                return@registerForActivityResult
            }

            val data = result.data
            if (data == null) {
                cancelPendingWorkflow("Signature placement was not returned")
                return@registerForActivityResult
            }

            pendingSignaturePlacement = SignaturePlacement(
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

            continueAfterSignaturePlacement()
        }

    private val qrPlacementLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK) {
                cancelPendingWorkflow("QR positioning cancelled")
                return@registerForActivityResult
            }

            val data = result.data
            if (data == null) {
                cancelPendingWorkflow("QR placement was not returned")
                return@registerForActivityResult
            }

            pendingQrPlacement = QrPlacement(
                pageIndex = data.getIntExtra(
                    QrPlacementEditorActivity.EXTRA_PAGE_INDEX,
                    0
                ),
                x = data.getFloatExtra(
                    QrPlacementEditorActivity.EXTRA_X,
                    0.68f
                ),
                y = data.getFloatExtra(
                    QrPlacementEditorActivity.EXTRA_Y,
                    0.72f
                ),
                width = data.getFloatExtra(
                    QrPlacementEditorActivity.EXTRA_WIDTH,
                    0.22f
                )
            ).normalized()

            finishPendingWorkflow()
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
            setupIssuerManagementButton()
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
    // ISSUER MANAGEMENT BUTTON
    // ---------------------------------------------------------

    private fun setupIssuerManagementButton() {

        val settingsSection = binding.sectionSettings as? ViewGroup
            ?: return

        val button = MaterialButton(this).apply {

            text = "Manage Trusted Issuers"
            isAllCaps = false
            textSize = 14f

            setOnClickListener {

                try {
                    startActivity(
                        Intent(
                            this@MainActivity,
                            IssuerManagementActivity::class.java
                        )
                    )
                } catch (e: Exception) {

                    Toast.makeText(
                        this@MainActivity,
                        "Unable to open issuer management: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val params = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 20
        }

        settingsSection.addView(button, params)
    }

    // ---------------------------------------------------------
    // STAMPING WORKFLOW
    // ---------------------------------------------------------

    private fun promptStampOptions(uri: Uri) {

        try {
            val fileName = fileHelper.getFileName(uri)

            val extension = fileName
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)

            val supported = extension in listOf(
                "pdf",
                "docx",
                "png",
                "jpg",
                "jpeg",
                "webp",
                "bmp"
            )

            if (!supported) {
                Toast.makeText(
                    this,
                    "Supported formats: PDF, DOCX and images",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            AlertDialog.Builder(this)
                .setTitle("Choose stamping method")
                .setItems(
                    arrayOf(
                        "QR Code Only",
                        "Signature Only",
                        "QR Code + Signature"
                    )
                ) { _, selected ->

                    val mode = when (selected) {
                        0 -> StampMode.QR_ONLY
                        1 -> StampMode.SIGNATURE_ONLY
                        else -> StampMode.QR_AND_SIGNATURE
                    }

                    beginStampWorkflow(uri, extension, mode)
                }
                .setNegativeButton("Cancel", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to read selected file: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun beginStampWorkflow(
        uri: Uri,
        extension: String,
        mode: StampMode
    ) {
        clearPendingWorkflow()

        pendingStampUri = uri
        pendingStampExtension = extension
        pendingStampMode = mode

        lifecycleScope.launch {

            try {
                showLoading(true, "Preparing stamping information...")

                val originalBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    } ?: throw Exception("Cannot read selected file")
                }

                if (originalBytes.isEmpty()) {
                    throw Exception("The selected file is empty")
                }

                val hash = withContext(Dispatchers.Default) {
                    securityManager.hashInputStream(
                        originalBytes.inputStream()
                    )
                }

                var pHash = ""

                if (mode.includesQr && extension !in listOf("pdf", "docx")) {
                    val bitmap = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(
                            originalBytes,
                            0,
                            originalBytes.size
                        )
                    }

                    if (bitmap != null) {
                        try {
                            pHash = withContext(Dispatchers.Default) {
                                securityManager.computePerceptualHash(bitmap)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }

                pendingOriginalHash = hash
                pendingIssuer = repository.getIssuerId()

                if (mode.includesQr) {
                    val payload = securityManager.createStamp(
                        hash,
                        pHash,
                        pendingIssuer
                    )

                    pendingPayloadSerialized = payload.serialize()
                    pendingTimestamp = payload.timestamp
                } else {
                    pendingPayloadSerialized = ""
                    pendingTimestamp = System.currentTimeMillis()
                }

                showLoading(false, null)

                if (mode.includesSignature) {
                    launchSignatureEditor()
                } else {
                    continueAfterSignaturePlacement()
                }

            } catch (e: Exception) {
                clearPendingWorkflow()
                showLoading(
                    false,
                    "Error: ${e.message ?: "Unable to prepare file"}"
                )
            }
        }
    }

    private fun launchSignatureEditor() {
        signatureEditorLauncher.launch(
            Intent(this, SignatureEditorActivity::class.java)
        )
    }

    private fun continueAfterSignatureCreation() {

        val uri = pendingStampUri
        val signatureFile = pendingSignatureFile

        if (uri == null || signatureFile == null) {
            cancelPendingWorkflow("Document or signature is missing")
            return
        }

        if (pendingStampExtension == "docx") {
            pendingSignaturePlacement = SignaturePlacement().normalized()
            continueAfterSignaturePlacement()
            return
        }

        launchSignaturePlacementEditor(uri, signatureFile)
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

    private fun continueAfterSignaturePlacement() {

        if (pendingStampMode.includesQr) {
            launchQrPlacementEditorIfSupported()
        } else {
            finishPendingWorkflow()
        }
    }

    private fun launchQrPlacementEditorIfSupported() {

        val uri = pendingStampUri

        if (uri == null) {
            cancelPendingWorkflow("Selected file is missing")
            return
        }

        if (pendingStampExtension == "docx") {
            pendingQrPlacement = QrPlacement().normalized()
            finishPendingWorkflow()
            return
        }

        if (pendingPayloadSerialized.isBlank()) {
            cancelPendingWorkflow("QR information is missing")
            return
        }

        val intent = Intent(
            this,
            QrPlacementEditorActivity::class.java
        ).apply {
            putExtra(
                QrPlacementEditorActivity.EXTRA_DOCUMENT_URI,
                uri
            )
            putExtra(
                QrPlacementEditorActivity.EXTRA_QR_CONTENT,
                pendingPayloadSerialized
            )
        }

        qrPlacementLauncher.launch(intent)
    }

    private fun finishPendingWorkflow() {

        val uri = pendingStampUri

        if (uri == null) {
            cancelPendingWorkflow("Selected file is missing")
            return
        }

        val signature = if (pendingStampMode.includesSignature) {
            val signatureFile = pendingSignatureFile

            if (signatureFile == null) {
                cancelPendingWorkflow("Signature image is missing")
                return
            }

            DocumentSignature(
                signatureFile = signatureFile,
                placement = pendingSignaturePlacement
            )
        } else {
            null
        }

        val payload = if (pendingStampMode.includesQr) {
            ReceiptPayload.deserialize(pendingPayloadSerialized)
                ?: run {
                    cancelPendingWorkflow("QR information is invalid")
                    return
                }
        } else {
            null
        }

        processStampCreation(
            fileUri = uri,
            mode = pendingStampMode,
            documentSignature = signature,
            payload = payload,
            qrPlacement = pendingQrPlacement,
            originalHash = pendingOriginalHash,
            issuer = pendingIssuer,
            timestamp = pendingTimestamp
        )

        clearPendingWorkflow()
    }

    private fun cancelPendingWorkflow(message: String) {
        clearPendingWorkflow()

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()

        showLoading(false, null)
    }

    private fun clearPendingWorkflow() {
        pendingStampUri = null
        pendingSignatureFile = null
        pendingStampExtension = ""
        pendingStampMode = StampMode.QR_ONLY
        pendingQrPlacement = QrPlacement()
        pendingSignaturePlacement = SignaturePlacement()
        pendingPayloadSerialized = ""
        pendingOriginalHash = ""
        pendingIssuer = ""
        pendingTimestamp = 0L
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
        mode: StampMode,
        documentSignature: DocumentSignature?,
        payload: ReceiptPayload?,
        qrPlacement: QrPlacement,
        originalHash: String,
        issuer: String,
        timestamp: Long
    ) {

        lifecycleScope.launch {

            showLoading(true, "Processing selected file...")
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
                    throw Exception("Unsupported file format")
                }

                if (mode.includesSignature) {
                    val signature = documentSignature
                        ?: throw Exception("Signature is missing")

                    if (!signature.isReadyForProcessing()) {
                        throw Exception("The signature image is invalid")
                    }
                }

                if (mode.includesQr && payload == null) {
                    throw Exception("QR payload is missing")
                }

                val serialized = payload?.serialize().orEmpty()

                if (mode.includesQr) {
                    showLoading(true, "Generating QR code...")

                    qrBitmap = withContext(Dispatchers.Default) {
                        securityManager.generateQRCodeBitmap(serialized)
                    } ?: throw Exception("Failed to generate QR code")
                }

                val dir = fileHelper.createStampsDirectory()

                val cleanBase = fileName
                    .substringBeforeLast('.')
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")

                val modeSuffix = when (mode) {
                    StampMode.QR_ONLY -> "QR"
                    StampMode.SIGNATURE_ONLY -> "Signature"
                    StampMode.QR_AND_SIGNATURE -> "QR_Signature"
                }

                val outputFile: File
                val outputMime: String
                val isDocumentStamp: Boolean

                if (extension == "pdf") {

                    outputFile = File(
                        dir,
                        "${cleanBase}_${timestamp}_${modeSuffix}.pdf"
                    )

                    withContext(Dispatchers.IO) {

                        when (mode) {

                            StampMode.QR_ONLY -> {
                                documentStampHelper.stampPdfWithQrPlacement(
                                    originalBytes,
                                    qrBitmap!!,
                                    qrPlacement,
                                    outputFile
                                )
                            }

                            StampMode.SIGNATURE_ONLY -> {
                                documentStampHelper.stampPdfSignatureOnly(
                                    originalBytes,
                                    documentSignature!!,
                                    outputFile
                                )
                            }

                            StampMode.QR_AND_SIGNATURE -> {
                                documentStampHelper
                                    .stampPdfWithSignatureAndQrPlacement(
                                        originalBytes,
                                        qrBitmap!!,
                                        qrPlacement,
                                        documentSignature!!,
                                        outputFile
                                    )
                            }
                        }
                    }

                    outputMime = "application/pdf"
                    isDocumentStamp = true

                } else if (extension == "docx") {

                    outputFile = File(
                        dir,
                        "${cleanBase}_${timestamp}_${modeSuffix}.docx"
                    )

                    withContext(Dispatchers.IO) {

                        when (mode) {

                            StampMode.QR_ONLY -> {
                                documentStampHelper.stampDocxWithQrPlacement(
                                    originalBytes,
                                    qrBitmap!!,
                                    qrPlacement,
                                    outputFile
                                )
                            }

                            StampMode.SIGNATURE_ONLY -> {
                                DocxSignatureProcessor.stampDocxWithSignature(
                                    originalBytes,
                                    documentSignature!!,
                                    outputFile
                                )
                            }

                            StampMode.QR_AND_SIGNATURE -> {
                                DocxSignatureProcessor.stampDocxWithSignature(
                                    originalBytes,
                                    documentSignature!!,
                                    outputFile
                                )

                                val signatureStampedBytes =
                                    outputFile.readBytes()

                                documentStampHelper.stampDocxWithQrPlacement(
                                    signatureStampedBytes,
                                    qrBitmap!!,
                                    qrPlacement,
                                    outputFile
                                )
                            }
                        }
                    }

                    outputMime =
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

                    isDocumentStamp = true

                } else {

                    val sourceBitmap = decodedBitmap
                        ?: throw Exception("Unable to decode image")

                    outputFile = File(
                        dir,
                        "${cleanBase}_${timestamp}_${modeSuffix}.png"
                    )

                    val sourceFile = File(
                        cacheDir,
                        "receiptshield_source_${System.currentTimeMillis()}.png"
                    )

                    try {
                        withContext(Dispatchers.IO) {
                            FileOutputStream(sourceFile).use { output ->
                                sourceBitmap.compress(
                                    Bitmap.CompressFormat.PNG,
                                    100,
                                    output
                                )
                            }
                        }

                        val configuration = StampConfiguration(
                            mode = mode,
                            qrPlacement = qrPlacement,
                            signature = documentSignature
                        )

                        withContext(Dispatchers.IO) {
                            ImageStampProcessor.stampImage(
                                sourceFile,
                                qrBitmap,
                                configuration,
                                outputFile
                            )
                        }

                    } finally {
                        sourceFile.delete()
                    }

                    outputMime = "image/png"
                    isDocumentStamp = false
                }

                if (!outputFile.exists() || outputFile.length() == 0L) {
                    throw Exception("Stamped output file was not created")
                }

                repository.saveRecord(
                    StampedRecord(
                        displayName = fileName,
                        outputFilePath = outputFile.absolutePath,
                        mimeType = outputMime,
                        isDocumentStamp = isDocumentStamp,
                        hash = originalHash,
                        issuerId = issuer,
                        timestamp = timestamp,
                        payloadSerialized = serialized
                    )
                )

                latestOutputFile = outputFile
                latestMimeType = outputMime

                binding.fileActionsRow.visibility = View.VISIBLE

                binding.tvStatus.setTextColor(
                    Color.parseColor("#0F9D58")
                )

                val modeDescription = when (mode) {
                    StampMode.QR_ONLY -> "QR Code Only"
                    StampMode.SIGNATURE_ONLY -> "Signature Only"
                    StampMode.QR_AND_SIGNATURE -> "QR Code + Signature"
                }

                binding.tvStatus.text =
                    "Stamp Processed!\n\n" +
                    "File: $fileName\n" +
                    "Output: ${outputFile.name}\n" +
                    "Mode: $modeDescription\n" +
                    "Issuer: $issuer\n" +
                    "Date: ${dateFormatter.format(Date(timestamp))}\n" +
                    "SHA-256: ${originalHash.take(24)}..."

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
    // CAMERA QR SCAN
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
                        "No ReceiptShield QR code found in the image."
                    return@launch
                }

                val payload = ReceiptPayload.deserialize(payloadString)

                if (payload == null) {
                    binding.tvVerifyStatus.setTextColor(Color.RED)
                    binding.tvVerifyStatus.text =
                        "Invalid QR format. This is not a ReceiptShield stamp."
                    return@launch
                }

                binding.tvVerifyStatus.setTextColor(
                    Color.parseColor("#0F9D58")
                )

                binding.tvVerifyStatus.text = """
                    VALID STAMP DETECTED

                    Issuer ID: ${payload.issuerId}
                    Issued: ${dateFormatter.format(Date(payload.timestamp))}

                    Note: Camera scans verify the Issuer and Date. To verify file integrity, use the "Pick File to Verify" button.
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

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 20
                }
            }

            val titleTv = TextView(this).apply {
                text = record.displayName
                setTextColor(Color.parseColor("#1A1C1E"))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }

            val modeLabel = when {
                record.payloadSerialized.isBlank() ->
                    "Signature Only"

                else ->
                    "QR-enabled stamp"
            }

            val typeLabel =
                if (record.isDocumentStamp) {
                    "Document • $modeLabel"
                } else {
                    "Image • $modeLabel"
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
