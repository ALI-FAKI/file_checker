package com.ali.receiptshield.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val signaturePath = Path()
    private val paths = mutableListOf<Path>()

    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3.2f)
    }

    private var lastX = 0f
    private var lastY = 0f

    private var hasSignatureInk = false

    var signatureColor: Int
        get() = drawPaint.color
        set(value) {
            drawPaint.color = value
            invalidate()
        }

    var signatureStrokeWidth: Float
        get() = drawPaint.strokeWidth /
            resources.displayMetrics.density

        set(value) {
            drawPaint.strokeWidth = dp(
                value.coerceIn(1f, 12f)
            )
            invalidate()
        }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
        contentDescription = "Signature drawing area"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (path in paths) {
            canvas.drawPath(path, drawPaint)
        }

        if (!signaturePath.isEmpty) {
            canvas.drawPath(signaturePath, drawPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                signaturePath.reset()
                signaturePath.moveTo(
                    event.x,
                    event.y
                )

                lastX = event.x
                lastY = event.y

                hasSignatureInk = true

                parent?.requestDisallowInterceptTouchEvent(true)

                invalidate()

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - lastX)
                val dy = abs(event.y - lastY)

                if (dx >= 1f || dy >= 1f) {

                    signaturePath.quadTo(
                        lastX,
                        lastY,
                        (event.x + lastX) / 2f,
                        (event.y + lastY) / 2f
                    )

                    lastX = event.x
                    lastY = event.y

                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_UP -> {

                signaturePath.lineTo(
                    event.x,
                    event.y
                )

                paths.add(Path(signaturePath))

                signaturePath.reset()

                parent?.requestDisallowInterceptTouchEvent(false)

                invalidate()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                signaturePath.reset()
                hasSignatureInk = paths.isNotEmpty()

                parent?.requestDisallowInterceptTouchEvent(false)

                invalidate()

                return true
            }
        }

        return true
    }

    /**
     * Clears all drawn signature strokes.
     */
    fun clear() {
        paths.clear()
        signaturePath.reset()
        hasSignatureInk = false
        invalidate()
    }

    /**
     * Returns true when the user has drawn a signature.
     */
    fun hasSignature(): Boolean {
        return hasSignatureInk &&
            (paths.isNotEmpty() || !signaturePath.isEmpty)
    }

    /**
     * Exports the signature as a bitmap with a transparent background.
     *
     * Returns null if no signature has been drawn or
     * the view has not yet been measured.
     */
    fun exportSignatureBitmap(): Bitmap? {

        if (width <= 0 || height <= 0 || !hasSignature()) {
            return null
        }

        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)

        canvas.drawColor(
            Color.TRANSPARENT,
            PorterDuff.Mode.CLEAR
        )

        for (path in paths) {
            canvas.drawPath(path, drawPaint)
        }

        if (!signaturePath.isEmpty) {
            canvas.drawPath(signaturePath, drawPaint)
        }

        return output
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
