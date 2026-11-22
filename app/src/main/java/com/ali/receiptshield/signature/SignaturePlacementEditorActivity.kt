
package com.ali.receiptshield.signature

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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class SignaturePlacementEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOCUMENT_URI = "document_uri"
        const val EXTRA_SIGNATURE_PATH = "signature_path"

        const val EXTRA_PAGE_INDEX = "signature_page_index"
        const val EXTRA_X = "signature_x"
        const val EXTRA_Y = "signature_y"
        const val EXTRA_WIDTH = "signature_width"
    }

    private lateinit var editorView: SignaturePlacementEditorView
    private lateinit var pageLabel: TextView
    private lateinit var navigation: LinearLayout

    private var documentUri: Uri? = null
    private var signatureBitmap: Bitmap? = null
    private var pageBitmap: Bitmap? = null

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    private var currentPage = 0
    private var totalPages = 1
    private var isPdf = false

    private var placement = SignaturePlacement()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        documentUri = intent.getParcelableExtra(EXTRA_DOCUMENT_URI)

        val signaturePath =
            intent.getStringExtra(EXTRA_SIGNATURE_PATH)

        if (documentUri == null || signaturePath.isNullOrBlank()) {
            finishWithError("Missing document or signature")
            return
        }

        val signatureFile = File(signaturePath)

        if (!signatureFile.exists() || signatureFile.length() == 0L) {
            finishWithError("Signature image is unavailable")
            return
        }

        signatureBitmap = BitmapFactory.decodeFile(signaturePath)

        if (signatureBitmap == null) {
            finishWithError("Unable to load signature image")
            return
        }

        try {
            isPdf = isPdfDocument(documentUri!!)

            createInterface()

            if (isPdf) {
                openPdf()
                renderCurrentPage()
            } else {
                openImage()
            }

        } catch (e: Exception) {
            finishWithError(
                "Could not open document: ${e.message}"
            )
        }
    }

    private fun isPdfDocument(uri: Uri): Boolean {
        val mimeType = contentResolver.getType(uri).orEmpty()

        return mimeType.equals("application/pdf", true) ||
            uri.toString().substringBefore("?")
                .lowercase()
                .endsWith(".pdf")
    }

    private fun openPdf() {
        val uri = documentUri
            ?: throw IllegalStateException("Missing PDF URI")

        fileDescriptor =
            contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open PDF")

        pdfRenderer = PdfRenderer(fileDescriptor!!)

        totalPages = pdfRenderer!!.pageCount

        if (totalPages <= 0) {
            throw IllegalStateException("PDF has no pages")
        }
    }

    private fun openImage() {
        val uri = documentUri
            ?: throw IllegalStateException("Missing image URI")

        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalStateException("Cannot read image")

        pageBitmap?.recycle()
        pageBitmap = bitmap

        totalPages = 1
        currentPage = 0

        editorView.setPagePreview(bitmap)

        signatureBitmap?.let {
            editorView.setSignature(it)
        }

        placement = placement.copy(pageIndex = 0).normalized()

        editorView.setPageIndex(0)
        editorView.setPlacement(placement)

        navigation.visibility = View.GONE
        pageLabel.visibility = View.GONE
    }

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(245, 246, 248))
        }

        val title = TextView(this).apply {
            text = "Position Your Signature"
            textSize = 21f
            setTextColor(Color.rgb(32, 33, 36))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }

        val instruction = TextView(this).apply {
            text = "Drag the signature to move it. Use the orange handle to resize it."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }

        editorView = SignaturePlacementEditorView(this)

        val previewParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val previousButton = Button(this).apply {
            text = "Previous"
            isAllCaps = false
            setOnClickListener {
                if (currentPage > 0) {
                    currentPage--
                    renderCurrentPage()
                }
            }
        }

        pageLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        val nextButton = Button(this).apply {
            text = "Next"
            isAllCaps = false
            setOnClickListener {
                if (currentPage < totalPages - 1) {
                    currentPage++
                    renderCurrentPage()
                }
            }
        }

        navigation.addView(
            previousButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        navigation.addView(
            pageLabel,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        navigation.addView(
            nextButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val sizeLabel = TextView(this).apply {
            text = "Signature size"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, 0)
        }

        val sizeSeekBar = SeekBar(this).apply {
            max = 100

            progress = (
                (placement.width - SignaturePlacement.MIN_WIDTH) /
                    (SignaturePlacement.MAX_WIDTH -
                        SignaturePlacement.MIN_WIDTH) * 100f
                ).toInt().coerceIn(0, 100)

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return

                        val width =
                            SignaturePlacement.MIN_WIDTH +
                                (progress / 100f) *
                                (
                                    SignaturePlacement.MAX_WIDTH -
                                        SignaturePlacement.MIN_WIDTH
                                    )

                        placement = placement.copy(
                            width = width
                        ).normalized()

                        editorView.setPlacement(placement)
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

        val applyButton = Button(this).apply {
            text = "Apply Signature Position"
            isAllCaps = false

            setOnClickListener {
                returnPlacement()
            }
        }

        root.addView(title)
        root.addView(instruction)
        root.addView(editorView, previewParams)
        root.addView(navigation)
        root.addView(pageLabel)
        root.addView(sizeLabel)
        root.addView(sizeSeekBar)

        root.addView(
            applyButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )

        setContentView(root)
    }

    private fun renderCurrentPage() {

        val renderer = pdfRenderer ?: return
        val signature = signatureBitmap ?: return

        if (currentPage !in 0 until renderer.pageCount) {
            return
        }

        try {
            val page = renderer.openPage(currentPage)

            val bitmap = try {
                Bitmap.createBitmap(
                    page.width,
                    page.height,
                    Bitmap.Config.ARGB_8888
                ).also {
                    it.eraseColor(Color.WHITE)

                    page.render(
                        it,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                }
            } finally {
                page.close()
            }

            pageBitmap?.recycle()
            pageBitmap = bitmap

            placement = placement.copy(
                pageIndex = currentPage
            ).normalized()

            editorView.setPagePreview(bitmap)
            editorView.setSignature(signature)
            editorView.setPlacement(placement)
            editorView.setPageIndex(currentPage)

            pageLabel.text =
                "Page ${currentPage + 1} of $totalPages"

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to display page: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun returnPlacement() {

        placement = editorView.getPlacement()
            .copy(
                pageIndex = if (isPdf) currentPage else 0
            )
            .normalized()

        val result = Intent().apply {
            putExtra(EXTRA_PAGE_INDEX, placement.pageIndex)
            putExtra(EXTRA_X, placement.x)
            putExtra(EXTRA_Y, placement.y)
            putExtra(EXTRA_WIDTH, placement.width)
        }

        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishWithError(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()

        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
            ).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()

        pageBitmap?.recycle()
        pageBitmap = null

        signatureBitmap?.recycle()
        signatureBitmap = null

        pdfRenderer?.close()
        pdfRenderer = null

        fileDescriptor?.close()
        fileDescriptor = null
    }
}
