package com.ali.receiptshield.signature

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class SignatureEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SIGNATURE_PATH = "signature_path"
        const val EXTRA_SIGNATURE_MODE = "signature_mode"

        const val MODE_DRAW = "draw"
        const val MODE_TYPED = "typed"
    }

    private lateinit var signaturePad: SignaturePadView
    private lateinit var typedNameInput: EditText
    private lateinit var typedPreview: TextView
    private lateinit var drawingSection: LinearLayout
    private lateinit var typedSection: LinearLayout
    private lateinit var modeLabel: TextView

    private var selectedMode = MODE_DRAW
    private var selectedColor = Color.BLACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
    }

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(245, 246, 248))
        }

        val title = TextView(this).apply {
            text = "Create Your Signature"
            textSize = 23f
            setTextColor(Color.rgb(32, 33, 36))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        modeLabel = TextView(this).apply {
            text = "Choose signature method"
            textSize = 15f
            setTextColor(Color.DKGRAY)
        }

        val modeButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val drawButton = Button(this).apply {
            text = "Draw"
            setOnClickListener {
                switchMode(MODE_DRAW)
            }
        }

        val typeButton = Button(this).apply {
            text = "Type Name"
            setOnClickListener {
                switchMode(MODE_TYPED)
            }
        }

        modeButtons.addView(
            drawButton,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        modeButtons.addView(
            typeButton,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        drawingSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val drawingTitle = TextView(this).apply {
            text = "Draw your signature below"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(8))
        }

        signaturePad = SignaturePadView(this).apply {
            setBackgroundColor(Color.WHITE)
            signatureColor = selectedColor
            signatureStrokeWidth = 3.2f
        }

        drawingSection.addView(drawingTitle)

        drawingSection.addView(
            signaturePad,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(230)
            )
        )

        val colorTitle = TextView(this).apply {
            text = "Signature colour"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(5))
        }

        val colorButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        addColorButton(colorButtons, Color.BLACK, "Black")
        addColorButton(colorButtons, Color.BLUE, "Blue")
        addColorButton(colorButtons, Color.rgb(25, 70, 150), "Navy")
        addColorButton(colorButtons, Color.RED, "Red")

        val thicknessTitle = TextView(this).apply {
            text = "Pen thickness"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(10), 0, 0)
        }

        val thicknessSeekBar = SeekBar(this).apply {
            max = 110
            progress = 22

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        signaturePad.signatureStrokeWidth =
                            1f + progress / 10f
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

        drawingSection.addView(colorTitle)
        drawingSection.addView(colorButtons)
        drawingSection.addView(thicknessTitle)
        drawingSection.addView(thicknessSeekBar)

        typedSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val typedTitle = TextView(this).apply {
            text = "Enter your name"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(8))
        }

        typedNameInput = EditText(this).apply {
            hint = "Your full name"
            textSize = 20f
            setSingleLine(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }

        typedSection.addView(typedTitle)
        typedSection.addView(
            typedNameInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        val typedPreviewTitle = TextView(this).apply {
            text = "Signature style preview"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(5))
        }

        typedPreview = TextView(this).apply {
            text = "Your signature will appear here"
            textSize = 27f
            typeface = Typeface.create(
                "cursive",
                Typeface.ITALIC
            )
            setTextColor(selectedColor)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(25), dp(8), dp(25))
        }

        typedNameInput.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    typedPreview.text =
                        s?.toString()?.ifBlank {
                            "Your signature will appear here"
                        } ?: ""
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        typedSection.addView(typedPreviewTitle)
        typedSection.addView(typedPreview)

        val actionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }

        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener {
                signaturePad.clear()
                typedNameInput.text.clear()
            }
        }

        val saveButton = Button(this).apply {
            text = "Save Signature"
            setOnClickListener {
                saveSignature()
            }
        }

        actionButtons.addView(
            clearButton,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        actionButtons.addView(
            saveButton,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        root.addView(title)
        root.addView(modeLabel)
        root.addView(modeButtons)
        root.addView(drawingSection)
        root.addView(typedSection)
        root.addView(actionButtons)

        setContentView(root)
    }

    private fun switchMode(mode: String) {
        selectedMode = mode

        drawingSection.visibility =
            if (mode == MODE_DRAW) {
                View.VISIBLE
            } else {
                View.GONE
            }

        typedSection.visibility =
            if (mode == MODE_TYPED) {
                View.VISIBLE
            } else {
                View.GONE
            }

        modeLabel.text =
            if (mode == MODE_DRAW) {
                "Draw using your finger"
            } else {
                "Create a typed signature"
            }
    }

    private fun addColorButton(
        container: LinearLayout,
        color: Int,
        label: String
    ) {
        val button = Button(this).apply {
            text = label
            setTextColor(color)

            setOnClickListener {
                selectedColor = color
                signaturePad.signatureColor = color
                if (::typedPreview.isInitialized) {
                    typedPreview.setTextColor(color)
                }
            }
        }

        container.addView(
            button,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )
    }

    private fun saveSignature() {

        val bitmap = when (selectedMode) {
            MODE_DRAW -> signaturePad.exportSignatureBitmap()
            MODE_TYPED -> createTypedSignatureBitmap(
                typedNameInput.text.toString()
            )
            else -> null
        }

        if (bitmap == null) {
            Toast.makeText(
                this,
                "Please create a signature first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            val savedFile = try {
                SignatureFileHelper.saveSignature(
                    context = applicationContext,
                    bitmap = bitmap,
                    fileName = "signature_${System.currentTimeMillis()}.png"
                )
            } catch (_: Exception) {
                val directory = File(
                    cacheDir,
                    "signatures"
                ).apply {
                    if (!exists()) mkdirs()
                }

                File(
                    directory,
                    "signature_${System.currentTimeMillis()}.png"
                ).also { outputFile ->
                    FileOutputStream(outputFile).use { stream ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                            throw IllegalStateException("Failed to encode signature image")
                        }
                        stream.flush()
                    }
                }
            }

            val result = Intent().apply {
                putExtra(
                    EXTRA_SIGNATURE_PATH,
                    savedFile.absolutePath
                )

                putExtra(
                    EXTRA_SIGNATURE_MODE,
                    selectedMode
                )
            }

            bitmap.recycle()

            setResult(Activity.RESULT_OK, result)
            finish()

        } catch (e: Exception) {
            bitmap.recycle()

            Toast.makeText(
                this,
                "Could not save signature: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun createTypedSignatureBitmap(
        name: String
    ): Bitmap? {

        if (name.isBlank()) return null

        val bitmap = Bitmap.createBitmap(
            1200,
            350,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)

        canvas.drawColor(
            Color.TRANSPARENT,
            PorterDuff.Mode.CLEAR
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = selectedColor
            textSize = 105f
            typeface = Typeface.create(
                "cursive",
                Typeface.ITALIC
            )
        }

        val textWidth = paint.measureText(name)
        val scale = minOf(
            1f,
            1080f / textWidth
        )

        canvas.save()
        canvas.scale(scale, 1f)

        canvas.drawText(
            name,
            25f,
            220f,
            paint
        )

        canvas.restore()

        return bitmap
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }
}
