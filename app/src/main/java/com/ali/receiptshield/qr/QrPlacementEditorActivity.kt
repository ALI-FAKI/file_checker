package com.ali.receiptshield.qr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ali.receiptshield.crypto.ReceiptSecurityManager

class QrPlacementEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOCUMENT_URI = "document_uri"
        const val EXTRA_QR_CONTENT = "qr_content"
        const val EXTRA_PAGE_INDEX = "page_index"
        const val EXTRA_X = "qr_x"
        const val EXTRA_Y = "qr_y"
        const val EXTRA_WIDTH = "qr_width"
    }

    private lateinit var editorView: QrPlacementEditorView
    private lateinit var pageLabel: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var sizeSeekBar: SeekBar
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button

    private var documentUri: Uri? = null
    private var qrContent: String = ""

    private var pdfRenderer: PdfRenderer? = null
    private var pdfFileDescriptor: ParcelFileDescriptor? = null
    private var currentPageIndex = 0
    private var totalPages = 1

    private var currentPlacement = QrPlacement()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        documentUri = intent.getParcelableExtra(EXTRA_DOCUMENT_URI)
        qrContent = intent.getStringExtra(EXTRA_QR_CONTENT) ?: ""

        if (documentUri == null || qrContent.isBlank()) {
            Toast.makeText(
                this,
                "Document or QR information is missing",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        currentPlacement = QrPlacement(
            pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0),
            x = intent.getFloatExtra(EXTRA_X, 0.68f),
            y = intent.getFloatExtra(EXTRA_Y, 0.72f),
            width = intent.getFloatExtra(EXTRA_WIDTH, 0.22f)
        ).normalized()

        createInterface()
        createQrCode()
        loadDocumentPreview()
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFF1F2F4.toInt())
        }

        val title = TextView(this).apply {
            text = "Customize QR Placement"
            textSize = 21f
            setTextColor(0xFF202124.toInt())
            setPadding(0, 0, 0, dp(12))
        }

        editorView = QrPlacementEditorView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        pageLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(10), 0, dp(5))
        }

        val pageControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        previousButton = Button(this).apply {
            text = "Previous"
            setOnClickListener {
                showPage(currentPageIndex - 1)
            }
        }

        nextButton = Button(this).apply {
            text = "Next"
            setOnClickListener {
                showPage(currentPageIndex + 1)
            }
        }

        pageControls.addView(previousButton)
        pageControls.addView(nextButton)

        sizeLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(10), 0, 0)
        }

        sizeSeekBar = SeekBar(this).apply {
            max = 59
            progress = widthToSeekBarProgress(currentPlacement.width)

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return

                        val width =
                            QrPlacement.MIN_WIDTH +
                                (progress / 59f) *
                                (QrPlacement.MAX_WIDTH - QrPlacement.MIN_WIDTH)

                        currentPlacement = currentPlacement.copy(
                            width = width
                        ).normalized()

                        editorView.setPlacement(currentPlacement)
                        updateSizeLabel()
                    }

                    override fun onStartTrackingTouch(
                        seekBar: SeekBar?
                    ) = Unit

                    override fun onStopTrackingTouch(
                        seekBar: SeekBar?
                    ) = Unit
                }
            )
        }

        val instructions = TextView(this).apply {
            text = "Drag the QR code to move it. Drag the orange corner handle to resize it."
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(5), 0, dp(8))
        }

        val actionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        val applyButton = Button(this).apply {
            text = "Apply Placement"
            setOnClickListener {
                applyPlacement()
            }
        }

        actionButtons.addView(cancelButton)
        actionButtons.addView(applyButton)

        root.addView(title)
        root.addView(editorView)
        root.addView(pageLabel)
        root.addView(pageControls)
        root.addView(sizeLabel)
        root.addView(sizeSeekBar)
        root.addView(instructions)
        root.addView(actionButtons)

        setContentView(root)

        editorView.onPlacementChanged = { placement ->
            currentPlacement = placement.normalized()
            sizeSeekBar.progress = widthToSeekBarProgress(currentPlacement.width)
            updateSizeLabel()
        }

        updateSizeLabel()
        updatePageLabel()
    }

    private fun widthToSeekBarProgress(width: Float): Int {
        val ratio = (width - QrPlacement.MIN_WIDTH) /
            (QrPlacement.MAX_WIDTH - QrPlacement.MIN_WIDTH)
        return (ratio * sizeSeekBar.max).toInt().coerceIn(0, sizeSeekBar.max)
    }

    private fun createQrCode() {
        try {
            val qrBitmap = ReceiptSecurityManager()
                .generateQRCodeBitmap(qrContent, 512, 512)

            editorView.setQrCode(qrBitmap)

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not generate QR code: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

            finish()
        }
    }

    private fun loadDocumentPreview() {
        val uri = documentUri ?: return

        try {
            val mimeType = contentResolver.getType(uri) ?: ""

            if (mimeType == "application/pdf" ||
                uri.toString().lowercase().endsWith(".pdf")
            ) {
                loadPdf(uri)
            } else {
                loadImage(uri)
            }

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not preview document: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

            finish()
        }
    }

    private fun loadPdf(uri: Uri) {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open PDF")

        pdfFileDescriptor = descriptor
        pdfRenderer = PdfRenderer(descriptor)

        totalPages = pdfRenderer!!.pageCount

        if (totalPages <= 0) {
            throw IllegalStateException("PDF has no pages")
        }

        showPage(
            currentPlacement.pageIndex.coerceIn(0, totalPages - 1)
        )
    }

    private fun loadImage(uri: Uri) {
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalStateException("Cannot read image")

        totalPages = 1
        currentPageIndex = 0

        editorView.setPagePreview(bitmap)
        editorView.setPageIndex(0)

        updatePageLabel()
    }

    private fun showPage(index: Int) {
        if (index !in 0 until totalPages) return

        currentPageIndex = index

        val renderer = pdfRenderer

        if (renderer != null) {
            val page = renderer.openPage(index)

            try {
                val scale = minOf(
                    2f,
                    1600f / page.width,
                    2000f / page.height
                )

                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )

                bitmap.eraseColor(Color.WHITE)

                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )

                editorView.setPagePreview(bitmap)

            } finally {
                page.close()
            }
        }

        currentPlacement = currentPlacement.copy(
            pageIndex = index
        )

        editorView.setPageIndex(index)
        editorView.setPlacement(currentPlacement)

        updatePageLabel()
    }

    private fun updatePageLabel() {
        pageLabel.text =
            "Page ${currentPageIndex + 1} of $totalPages"
        previousButton.isEnabled = currentPageIndex > 0
        nextButton.isEnabled = currentPageIndex < totalPages - 1
    }

    private fun updateSizeLabel() {
        val percentage =
            (currentPlacement.width * 100).toInt()

        sizeLabel.text = "QR code size: $percentage% of page width"
    }

    private fun applyPlacement() {
        currentPlacement = editorView.getPlacement().copy(
            pageIndex = currentPageIndex
        ).normalized()

        val result = Intent().apply {
            putExtra(EXTRA_PAGE_INDEX, currentPlacement.pageIndex)
            putExtra(EXTRA_X, currentPlacement.x)
            putExtra(EXTRA_Y, currentPlacement.y)
            putExtra(EXTRA_WIDTH, currentPlacement.width)
        }

        setResult(Activity.RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()

        pdfRenderer?.close()
        pdfFileDescriptor?.close()

        pdfRenderer = null
        pdfFileDescriptor = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
