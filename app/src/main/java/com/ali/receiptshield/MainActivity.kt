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
import com.ali.receiptshield.crypto.DocumentFormatV4
import com.ali.receiptshield.crypto.PlacementMetaV4
import com.ali.receiptshield.crypto.RECEIPT_VERSION_V4
import com.ali.receiptshield.crypto.ReceiptPayloadV4
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.crypto.RsV4IntegrityManager
import com.ali.receiptshield.crypto.SignatureMetaV4
import com.ali.receiptshield.crypto.StampModeV4
import com.ali.receiptshield.crypto.TrustedIssuerRegistry
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
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securityManager: ReceiptSecurityManager
    private lateinit var integrityManager: RsV4IntegrityManager
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
            integrityManager = RsV4IntegrityManager(securityManager)
            repository = StampRepository(this)
            fileHelper = StampFileHelper(this)

            documentStampHelper = DocumentStampHelper(
                this,
                securityManager,
                integrityManager
            )

            verificationHelper = VerificationHelper(
                this,
                securityManager,
                dateFormatter,
                documentStampHelper,
                integrityManager
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
                showLoading(true, "Calculating original document digest...")

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

                pendingOriginalHash = hash
                pendingIssuer = repository.getIssuerId()
                pendingTimestamp = System.currentTimeMillis()

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
                "$RECEIPT_VERSION_V4:PENDING_PLACEMENT"
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

        val receiptId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val issuer = pendingIssuer.ifBlank { repository.getIssuerId() }

        processStampCreation(
            fileUri = uri,
            receiptId = receiptId,
            mode = pendingStampMode,
            documentSignature = signature,
            qrPlacement = pendingQrPlacement,
            originalHash = pendingOriginalHash,
            issuer = issuer,
            timestamp = timestamp
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
    // CREATE RS_V4 STAMP AFTER PLACEMENT IS FINALIZED
    // ---------------------------------------------------------

    private fun processStampCreation(
        fileUri: Uri,
        receiptId: String,
        mode: StampMode,
        documentSignature: DocumentSignature?,
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

                val formatV4 = DocumentFormatV4.fromFileNameOrMime(fileName, mime)
                    ?: throw Exception("Unsupported file format")

                decodedBitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(
                        originalBytes,
                        0,
                        originalBytes.size
                    )
                }

                val isImage = decodedBitmap != null
                if (!isImage && formatV4 !in listOf(DocumentFormatV4.PDF, DocumentFormatV4.DOCX)) {
                    throw Exception("Unsupported file format")
                }

                if (mode.includesSignature) {
                    val signature = documentSignature
                        ?: throw Exception("Signature is missing")

                    if (!signature.isReadyForProcessing()) {
                        throw Exception("The signature image is invalid")
                    }
                }

                val stampModeV4 = when (mode) {
                    StampMode.QR_ONLY -> StampModeV4.QR_ONLY
                    StampMode.SIGNATURE_ONLY -> StampModeV4.SIGNATURE_ONLY
                    StampMode.QR_AND_SIGNATURE -> StampModeV4.QR_AND_SIGNATURE
                }

                val qrPlacementMeta = if (mode.includesQr) {
                    val norm = qrPlacement.normalized()
                    PlacementMetaV4(
                        pageIndex = norm.pageIndex,
                        xRatio = norm.x.toDouble(),
                        yRatio = norm.y.toDouble(),
                        widthRatio = norm.width.toDouble(),
                        heightRatio = norm.height.toDouble() // FIXED: Was norm.width
                    )
                } else {
                    null
                }

                val signatureMeta = if (mode.includesSignature) {
                    val sig = documentSignature!!
                    val norm = sig.placement.normalized()
                    val sigDigest = withContext(Dispatchers.IO) {
                        securityManager.sha256Bytes(sig.signatureFile.readBytes())
                    }
                    SignatureMetaV4(
                        placement = PlacementMetaV4(
                            pageIndex = norm.pageIndex,
                            xRatio = norm.x.toDouble(),
                            yRatio = norm.y.toDouble(),
                            widthRatio = norm.width.toDouble(),
                            heightRatio = norm.height.toDouble()
                        ),
                        signatureDigestSha256 = sigDigest
                    )
                } else {
                    null
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
                var payloadSerialized = ""

                if (formatV4 == DocumentFormatV4.PDF) {
                    outputFile = File(
                        dir,
                        "${cleanBase}_${timestamp}_${modeSuffix}.pdf"
                    )

                    if (mode.includesQr) {
                        val provisionalManifest = integrityManager.buildManifest(
                            receiptId = receiptId,
                            documentFormat = DocumentFormatV4.PDF,
                            stampMode = stampModeV4,
                            originalDocSha256 = originalHash,
                            issuerId = issuer,
                            qrPlacement = qrPlacementMeta,
                            signatureMeta = signatureMeta,
                            injectedArtifacts = emptyList(),
                            createdAtMillis = timestamp
                        )
                        val manifestSha = integrityManager.computeManifestDigest(provisionalManifest)
                        val payloadV4 = securityManager.createStampV4(
                            receiptId = receiptId,
                            documentFormat = DocumentFormatV4.PDF,
                            originalDocSha256 = originalHash,
                            manifestSha256 = manifestSha,
                            issuerId = issuer,
                            qrPlacement = qrPlacementMeta,
                            signatureMeta = signatureMeta,
                            timestampMillis = timestamp
                        )
                        payloadSerialized = payloadV4.toQrJsonString()
                        qrBitmap = withContext(Dispatchers.Default) {
                            securityManager.generateQRCodeBitmap(payloadSerialized)
                        } ?: throw Exception("Failed to generate QR code")
                    }

                    withContext(Dispatchers.IO) {
                        documentStampHelper.stampPdfV4(
                            originalBytes = originalBytes,
                            receiptId = receiptId,
                            originalDocSha256 = originalHash,
                            issuerId = issuer,
                            stampMode = stampModeV4,
                            qrBitmap = qrBitmap,
                            qrPlacement = if (mode.includesQr) qrPlacement else null,
                            signature = documentSignature,
                            signatureMeta = signatureMeta,
                            outputFile = outputFile
                        )
                    }

                    outputMime = "application/pdf"
                    isDocumentStamp = true

                } else if (formatV4 == DocumentFormatV4.DOCX) {
                    outputFile = File(
                        dir,
                        "${cleanBase}_${timestamp}_${modeSuffix}.docx"
                    )

                    val docxInputBytes = if (mode.includesSignature) {
                        val sigFile = File(cacheDir, "rs_v4_docx_sig_${System.currentTimeMillis()}.docx")
                        try {
                            withContext(Dispatchers.IO) {
                                DocxSignatureProcessor.stampDocxWithSignature(
                                    originalBytes,
                                    documentSignature!!,
                                    sigFile
                                )
                                sigFile.readBytes()
                            }
                        } finally {
                            if (sigFile.exists()) sigFile.delete()
                        }
                    } else {
                        originalBytes
                    }

                    if (mode.includesQr) {
                        val provisionalManifest = integrityManager.buildManifest(
                            receiptId = receiptId,
                            documentFormat = DocumentFormatV4.DOCX,
                            stampMode = stampModeV4,
                            originalDocSha256 = originalHash,
                            issuerId = issuer,
                            qrPlacement = qrPlacementMeta,
                            signatureMeta = signatureMeta,
                            injectedArtifacts = emptyList(),
                            createdAtMillis = timestamp
                        )
                        val manifestSha = integrityManager.computeManifestDigest(provisionalManifest)
                        val payloadV4 = securityManager.createStampV4(
                            receiptId = receiptId,
                            documentFormat = DocumentFormatV4.DOCX,
                            originalDocSha256 = originalHash,
                            manifestSha256 = manifestSha,
                            issuerId = issuer,
                            qrPlacement = qrPlacementMeta,
                            signatureMeta = signatureMeta,
                            timestampMillis = timestamp
                        )
                        payloadSerialized = payloadV4.toQrJsonString()
                        qrBitmap = withContext(Dispatchers.Default) {
                            securityManager.generateQRCodeBitmap(payloadSerialized)
                        } ?: throw Exception("Failed to generate QR code")
                    }

                    withContext(Dispatchers.IO) {
                        documentStampHelper.stampDocxV4(
                            originalBytes = docxInputBytes,
                            receiptId = receiptId,
                            originalDocSha256 = originalHash,
                            issuerId = issuer,
                            stampMode = stampModeV4,
                            qrBitmap = qrBitmap,
                            qrPlacement = if (mode.includesQr) qrPlacement else null,
                            signatureMeta = signatureMeta,
                            outputFile = outputFile
                        )
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

                        if (mode.includesQr) {
                            val provisionalManifest = integrityManager.buildManifest(
                                receiptId = receiptId,
                                documentFormat = formatV4,
                                stampMode = stampModeV4,
                                originalDocSha256 = originalHash,
                                issuerId = issuer,
                                qrPlacement = qrPlacementMeta,
                                signatureMeta = signatureMeta,
                                injectedArtifacts = emptyList(),
                                createdAtMillis = timestamp
                            )
                            val manifestSha = integrityManager.computeManifestDigest(provisionalManifest)
                            val payloadV4 = securityManager.createStampV4(
                                receiptId = receiptId,
                                documentFormat = formatV4,
                                originalDocSha256 = originalHash,
                                manifestSha256 = manifestSha,
                                issuerId = issuer,
                                qrPlacement = qrPlacementMeta,
                                signatureMeta = signatureMeta,
                                timestampMillis = timestamp
                            )
                            payloadSerialized = payloadV4.toQrJsonString()
                            qrBitmap = withContext(Dispatchers.Default) {
                                securityManager.generateQRCodeBitmap(payloadSerialized)
                            } ?: throw Exception("Failed to generate QR code")
                        }

                        val configuration = StampConfiguration(
                            mode = mode,
                            qrPlacement = qrPlacement,
                            signature = documentSignature
                        )

                        withContext(Dispatchers.IO) {
                            ImageStampProcessor.stampImageV4(
                                originalImage = sourceFile,
                                qrBitmap = qrBitmap,
                                configuration = configuration,
                                receiptId = receiptId,
                                originalDocSha256 = originalHash,
                                issuerId = issuer,
                                outputFile = outputFile,
                                securityManager = securityManager,
                                integrityManager = integrityManager
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
                        payloadSerialized = payloadSerialized
                    )
                )

                latestOutputFile = outputFile
                latestMimeType = outputMime

                binding.fileActionsRow.visibility = View.VISIBLE

                binding.tvStatus.setTextColor(
                    Color.parseColor("#0F9D58")
                )

                val modeDescription = when (mode) {
                    StampMode.QR_ONLY -> "QR Code Only ($RECEIPT_VERSION_V4)"
                    StampMode.SIGNATURE_ONLY -> "Signature Only ($RECEIPT_VERSION_V4)"
                    StampMode.QR_AND_SIGNATURE -> "QR Code + Signature ($RECEIPT_VERSION_V4)"
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
                "Running $RECEIPT_VERSION_V4 integrity and binding checks..."

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
    // CAMERA QR SCAN - DIGITAL SIGNATURE + TRUST CHECK ONLY
    // ---------------------------------------------------------

    private fun processCameraScan(bitmap: Bitmap) {

        lifecycleScope.launch {

            binding.progressBar.visibility = View.VISIBLE

            binding.tvVerifyStatus.setTextColor(
                Color.parseColor("#3C4043")
            )

            binding.tvVerifyStatus.text =
                "Scanning $RECEIPT_VERSION_V4 QR code and checking digital signature..."

            try {

                val payloadString = withContext(Dispatchers.Default) {
                    securityManager.decodeQRCode(bitmap)
                }

                if (payloadString.isNullOrBlank()) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvVerifyStatus.setTextColor(Color.RED)
                    binding.tvVerifyStatus.text =
                        "No $RECEIPT_VERSION_V4 QR code found in the image."
                    return@launch
                }

                val payload = try {
                    ReceiptPayloadV4.fromQrJsonString(payloadString)
                } catch (_: Exception) {
                    null
                }

                if (payload == null) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvVerifyStatus.setTextColor(Color.RED)
                    binding.tvVerifyStatus.text =
                        "UNSUPPORTED OR INVALID QR PAYLOAD\n\nOnly $RECEIPT_VERSION_V4 payloads are accepted."
                    return@launch
                }

                val signatureValid = withContext(Dispatchers.Default) {
                    securityManager.verifyPayloadSignatureV4(payload)
                }

                if (!signatureValid) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvVerifyStatus.setTextColor(Color.RED)

                    binding.tvVerifyStatus.text = """
                        INVALID DIGITAL SIGNATURE

                        Issuer ID: ${payload.issuerId}
                        Issued: ${dateFormatter.format(Date(payload.timestampMillis))}

                        The $RECEIPT_VERSION_V4 digital signature could not be verified.
                        This QR payload must not be treated as authentic.
                    """.trimIndent()

                    return@launch
                }

                // FIXED: Replaced reflection with direct method call
                val trusted = withContext(Dispatchers.IO) {
                    val registry = TrustedIssuerRegistry(applicationContext)
                    registry.isTrustedIssuer(payload.issuerId, payload.publicKeyBase64)
                }

                binding.progressBar.visibility = View.GONE

                if (trusted) {

                    binding.tvVerifyStatus.setTextColor(
                        Color.parseColor("#0F9D58")
                    )

                    binding.tvVerifyStatus.text = """
                        QR SIGNATURE VALID — TRUSTED ISSUER (FILE NOT CHECKED)

                        Receipt ID: ${payload.receiptId}
                        Format: ${payload.documentFormat.wireName}
                        Issuer ID: ${payload.issuerId}
                        Issued: ${dateFormatter.format(Date(payload.timestampMillis))}

                        Camera scan verified the $RECEIPT_VERSION_V4 QR signature and issuer trust.
                        Document content integrity and QR-to-document binding require
                        selecting the document file via Pick File to Verify.
                    """.trimIndent()

                } else {

                    binding.tvVerifyStatus.setTextColor(
                        Color.rgb(230, 145, 0)
                    )

                    binding.tvVerifyStatus.text = """
                        SIGNATURE VALID — ISSUER NOT TRUSTED

                        Receipt ID: ${payload.receiptId}
                        Format: ${payload.documentFormat.wireName}
                        Issuer ID: ${payload.issuerId}
                        Issued: ${dateFormatter.format(Date(payload.timestampMillis))}

                        The $RECEIPT_VERSION_V4 signature is valid, but this issuer's
                        public key is not registered as trusted on this device.

                        Camera scanning alone also does not verify document integrity.
                    """.trimIndent()
                }

            } catch (e: Exception) {

                binding.progressBar.visibility = View.GONE
                binding.tvVerifyStatus.setTextColor(Color.RED)

                binding.tvVerifyStatus.text =
                    "Camera verification failed:\n${e.message}"

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
                    "Signature Only ($RECEIPT_VERSION_V4)"

                else ->
                    "QR-enabled ($RECEIPT_VERSION_V4)"
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
