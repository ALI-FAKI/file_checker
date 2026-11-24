package com.ali.receiptshield.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SignaturePlacementEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pageBitmap: Bitmap? = null
    private var signatureBitmap: Bitmap? = null

    private var placement = SignaturePlacement()

    private val pageRect = RectF()
    private val signatureRect = RectF()
    private val handleRect = RectF()

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val signaturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 107, 53)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = null
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 107, 53)
        style = Paint.Style.FILL
    }

    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val handleRadius = dp(9f)
    private val touchPadding = dp(24f)

    private enum class TouchMode {
        NONE,
        MOVE,
        RESIZE
    }

    private var touchMode = TouchMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    var onPlacementChanged: ((SignaturePlacement) -> Unit)? = null

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    fun setPagePreview(bitmap: Bitmap?) {
        pageBitmap = bitmap
        requestLayout()
        invalidate()
    }

    fun setSignature(bitmap: Bitmap?) {
        signatureBitmap = bitmap
        invalidate()
    }

    fun setPlacement(value: SignaturePlacement) {
        placement = value.normalized()
        invalidate()
    }

    fun getPlacement(): SignaturePlacement {
        return placement.normalized()
    }

    fun setPageIndex(index: Int) {
        placement = placement.copy(
            pageIndex = index.coerceAtLeast(0)
        ).normalized()
        invalidate()
        onPlacementChanged?.invoke(placement)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawPage(canvas)
        drawSignature(canvas)
    }

    private fun drawPage(canvas: Canvas) {
        val bitmap = pageBitmap ?: return

        if (bitmap.width <= 0 || bitmap.height <= 0) return

        val availableWidth = width.toFloat()
        val availableHeight = height.toFloat()

        if (availableWidth <= 0f || availableHeight <= 0f) return

        val scale = min(
            availableWidth / bitmap.width,
            availableHeight / bitmap.height
        )

        val displayWidth = bitmap.width * scale
        val displayHeight = bitmap.height * scale

        val left = (availableWidth - displayWidth) / 2f
        val top = (availableHeight - displayHeight) / 2f

        pageRect.set(
            left,
            top,
            left + displayWidth,
            top + displayHeight
        )

        canvas.drawRect(pageRect, pagePaint)

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawBitmap(
            bitmap,
            null,
            pageRect,
            bitmapPaint
        )

        canvas.drawRect(pageRect, borderPaint)
    }

    private fun drawSignature(canvas: Canvas) {
        val signature = signatureBitmap ?: return

        if (pageRect.isEmpty || signature.width <= 0 || signature.height <= 0) {
            return
        }

        val normalized = placement.normalized()

        val signatureWidth = pageRect.width() * normalized.width

        val aspectRatio = signature.height.toFloat() /
            signature.width.toFloat()

        val signatureHeight = signatureWidth * aspectRatio

        val left = pageRect.left + pageRect.width() * normalized.x
        val top = pageRect.top + pageRect.height() * normalized.y

        signatureRect.set(
            left,
            top,
            left + signatureWidth,
            top + signatureHeight
        )

        canvas.drawBitmap(
            signature,
            null,
            signatureRect,
            signaturePaint
        )

        canvas.drawRect(signatureRect, selectionPaint)

        val handleX = signatureRect.right
        val handleY = signatureRect.bottom

        canvas.drawCircle(
            handleX,
            handleY,
            handleRadius,
            handlePaint
        )

        canvas.drawCircle(
            handleX,
            handleY,
            handleRadius,
            handleBorderPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageBitmap == null || signatureBitmap == null || pageRect.isEmpty) {
            return false
        }

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y

                updateSignatureRect()

                handleRect.set(
                    signatureRect.right - touchPadding,
                    signatureRect.bottom - touchPadding,
                    signatureRect.right + touchPadding,
                    signatureRect.bottom + touchPadding
                )

                touchMode = when {
                    handleRect.contains(event.x, event.y) ->
                        TouchMode.RESIZE

                    RectF(
                        signatureRect.left - touchPadding,
                        signatureRect.top - touchPadding,
                        signatureRect.right + touchPadding,
                        signatureRect.bottom + touchPadding
                    ).contains(event.x, event.y) ->
                        TouchMode.MOVE

                    else -> TouchMode.NONE
                }

                if (touchMode != TouchMode.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY

                when (touchMode) {

                    TouchMode.MOVE -> {
                        moveSignature(dx, dy)
                    }

                    TouchMode.RESIZE -> {
                        resizeSignature(dx, dy)
                    }

                    TouchMode.NONE -> return false
                }

                lastTouchX = event.x
                lastTouchY = event.y

                invalidate()
                onPlacementChanged?.invoke(getPlacement())

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val handled = touchMode != TouchMode.NONE

                touchMode = TouchMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)

                return handled
            }
        }

        return true
    }

    private fun updateSignatureRect() {
        val signature = signatureBitmap ?: return

        if (pageRect.isEmpty) return

        val normalized = placement.normalized()

        val signatureWidth = pageRect.width() * normalized.width

        val aspectRatio = signature.height.toFloat() /
            signature.width.toFloat()

        val signatureHeight = signatureWidth * aspectRatio

        val left = pageRect.left + pageRect.width() * normalized.x
        val top = pageRect.top + pageRect.height() * normalized.y

        signatureRect.set(
            left,
            top,
            left + signatureWidth,
            top + signatureHeight
        )
    }

    /**
     * Computes the local (display-only) normalized height for the signature box,
     * based on the signature's own aspect ratio. This is used only to clamp y
     * positions; it is NOT stored in SignaturePlacement, because the actual
     * height at stamp time is calculated from the real aspect ratio.
     */
    private fun computeNormalizedHeight(
        widthRatio: Float,
        signature: Bitmap,
        pageWidth: Float,
        pageHeight: Float
    ): Float {
        if (signature.width <= 0 || pageHeight <= 0f) return 0.05f
        val aspectRatio = signature.height.toFloat() / signature.width.toFloat()
        return ((widthRatio * pageWidth * aspectRatio) / pageHeight).coerceIn(0.01f, 1f)
    }

    private fun moveSignature(dx: Float, dy: Float) {
        val signature = signatureBitmap ?: return

        val pageWidth = pageRect.width()
        val pageHeight = pageRect.height()

        if (pageWidth <= 0f || pageHeight <= 0f) return

        val normalized = placement.normalized()

        val signatureWidth = normalized.width
        // Local height (not stored) used to prevent overflow when dragging.
        val signatureHeight = computeNormalizedHeight(
            widthRatio = signatureWidth,
            signature = signature,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )

        val newX = (
            normalized.x + dx / pageWidth
        ).coerceIn(0f, 1f - signatureWidth)

        val newY = (
            normalized.y + dy / pageHeight
        ).coerceIn(0f, max(0f, 1f - signatureHeight))

        // FIXED: Removed height = signatureHeight from copy()
        placement = normalized.copy(
            x = newX,
            y = newY
        ).normalized()
    }

    private fun resizeSignature(dx: Float, dy: Float) {
        val signature = signatureBitmap ?: return

        val pageWidth = pageRect.width()
        val pageHeight = pageRect.height()

        if (pageWidth <= 0f || pageHeight <= 0f) return

        val aspectRatio = signature.height.toFloat() /
            signature.width.toFloat()

        val normalized = placement.normalized()

        val widthChange = dx / pageWidth
        val heightChange = dy / (pageHeight * aspectRatio)

        val requestedWidth = normalized.width +
            max(widthChange, heightChange)

        val maxWidthByHeight = if (aspectRatio > 0f) {
            (1f - normalized.y) * pageHeight /
                (pageWidth * aspectRatio)
        } else {
            SignaturePlacement.MAX_WIDTH
        }

        val safeMaximum = min(
            SignaturePlacement.MAX_WIDTH,
            maxWidthByHeight
        ).coerceAtLeast(SignaturePlacement.MIN_WIDTH)

        val newWidth = requestedWidth.coerceIn(
            SignaturePlacement.MIN_WIDTH,
            safeMaximum
        )

        // Local height (not stored) used to prevent overflow when resizing.
        val newHeight = computeNormalizedHeight(
            widthRatio = newWidth,
            signature = signature,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )

        // FIXED: Removed height = newHeight from copy()
        placement = normalized.copy(
            width = newWidth,
            x = normalized.x.coerceIn(0f, 1f - newWidth),
            y = normalized.y.coerceIn(0f, max(0f, 1f - newHeight))
        ).normalized()
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
