
package com.ali.receiptshield.qr

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

class QrPlacementEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pageBitmap: Bitmap? = null
    private var qrBitmap: Bitmap? = null

    private var placement = QrPlacement()

    private val pageRect = RectF()
    private val qrRect = RectF()

    private val pagePaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val qrPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 123, 54)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 123, 54)
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        style = Paint.Style.FILL
    }

    private enum class DragMode {
        NONE,
        MOVE,
        RESIZE
    }

    private var dragMode = DragMode.NONE

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var startWidthPx = 0f
    private var startQrLeft = 0f
    private var startQrTop = 0f

    var onPlacementChanged: ((QrPlacement) -> Unit)? = null

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        contentDescription =
            "QR placement editor. Drag the QR code to move it or drag its bottom-right handle to resize it."
    }

    fun setPagePreview(bitmap: Bitmap?) {
        pageBitmap = bitmap
        invalidate()
    }

    fun setQrCode(bitmap: Bitmap?) {
        qrBitmap = bitmap
        invalidate()
    }

    fun setPlacement(value: QrPlacement) {
        placement = value.normalized()
        invalidate()
    }

    fun getPlacement(): QrPlacement {
        return placement.normalized()
    }

    fun setPageIndex(index: Int) {
        placement = placement.copy(
            pageIndex = index.coerceAtLeast(0)
        )

        onPlacementChanged?.invoke(placement)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.rgb(238, 240, 243))

        val page = pageBitmap

        if (page == null || page.width <= 0 || page.height <= 0) {
            drawEmptyPage(canvas)
            return
        }

        calculatePageRect(page)

        canvas.drawRoundRect(
            pageRect.left + dp(2f),
            pageRect.top + dp(3f),
            pageRect.right + dp(2f),
            pageRect.bottom + dp(3f),
            dp(3f),
            dp(3f),
            shadowPaint
        )

        canvas.drawBitmap(
            page,
            null,
            pageRect,
            pagePaint
        )

        val qr = qrBitmap ?: return

        calculateQrRect()

        canvas.drawBitmap(
            qr,
            null,
            qrRect,
            qrPaint
        )

        canvas.drawRect(
            qrRect,
            borderPaint
        )

        val handleRadius = dp(8f)

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        canvas.drawCircle(
            qrRect.right,
            qrRect.bottom,
            handleRadius + dp(2f),
            whitePaint
        )

        canvas.drawCircle(
            qrRect.right,
            qrRect.bottom,
            handleRadius,
            handlePaint
        )
    }

    private fun drawEmptyPage(canvas: Canvas) {
        val inset = dp(24f)

        val rect = RectF(
            inset,
            inset,
            width - inset,
            height - inset
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        canvas.drawRoundRect(
            rect,
            dp(4f),
            dp(4f),
            paint
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = dp(14f)
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(
            "Load a document page preview",
            rect.centerX(),
            rect.centerY(),
            textPaint
        )
    }

    private fun calculatePageRect(bitmap: Bitmap) {
        val horizontalInset = dp(16f)
        val verticalInset = dp(16f)

        val availableWidth = max(
            1f,
            width - horizontalInset * 2f
        )

        val availableHeight = max(
            1f,
            height - verticalInset * 2f
        )

        val scale = min(
            availableWidth / bitmap.width,
            availableHeight / bitmap.height
        )

        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale

        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f

        pageRect.set(
            left,
            top,
            left + drawnWidth,
            top + drawnHeight
        )
    }

    private fun calculateQrRect() {
        val normalized = placement.normalized()

        val size = pageRect.width() * normalized.width

        val left =
            pageRect.left + pageRect.width() * normalized.x

        val top =
            pageRect.top + pageRect.height() * normalized.y

        qrRect.set(
            left,
            top,
            left + size,
            top + size
        )

        if (qrRect.right > pageRect.right) {
            qrRect.offset(
                pageRect.right - qrRect.right,
                0f
            )
        }

        if (qrRect.bottom > pageRect.bottom) {
            qrRect.offset(
                0f,
                pageRect.bottom - qrRect.bottom
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageBitmap == null || qrBitmap == null) {
            return false
        }

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                calculatePageRect(pageBitmap!!)
                calculateQrRect()

                lastTouchX = event.x
                lastTouchY = event.y

                startWidthPx = qrRect.width()
                startQrLeft = qrRect.left
                startQrTop = qrRect.top

                val handleRadius = dp(20f)

                val nearHandle = kotlin.math.hypot(
                    (event.x - qrRect.right).toDouble(),
                    (event.y - qrRect.bottom).toDouble()
                ) <= handleRadius

                dragMode = when {
                    nearHandle -> DragMode.RESIZE
                    qrRect.contains(event.x, event.y) -> DragMode.MOVE
                    else -> DragMode.NONE
                }

                if (dragMode != DragMode.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) {
                    return false
                }

                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY

                if (dragMode == DragMode.MOVE) {

                    val newLeft = (
                        startQrLeft + dx
                    ).coerceIn(
                        pageRect.left,
                        pageRect.right - qrRect.width()
                    )

                    val newTop = (
                        startQrTop + dy
                    ).coerceIn(
                        pageRect.top,
                        pageRect.bottom - qrRect.height()
                    )

                    placement = placement.copy(
                        x = (
                            (newLeft - pageRect.left) /
                                pageRect.width()
                            ).coerceIn(0f, 1f),

                        y = (
                            (newTop - pageRect.top) /
                                pageRect.height()
                            ).coerceIn(0f, 1f)
                    ).normalized()

                } else {

                    val requestedSize = max(
                        dp(32f),
                        startWidthPx + max(dx, dy)
                    )

                    val maxSize =
                        min(
                            pageRect.width(),
                            pageRect.height()
                        ) * QrPlacement.MAX_WIDTH

                    val minSize =
                        pageRect.width() * QrPlacement.MIN_WIDTH

                    val newSize = requestedSize.coerceIn(
                        minSize,
                        max(minSize, maxSize)
                    )

                    val maxLeft = pageRect.right - newSize
                    val maxTop = pageRect.bottom - newSize

                    val left = startQrLeft.coerceIn(
                        pageRect.left,
                        maxLeft
                    )

                    val top = startQrTop.coerceIn(
                        pageRect.top,
                        maxTop
                    )

                    placement = placement.copy(
                        x = (
                            left - pageRect.left
                        ) / pageRect.width(),

                        y = (
                            top - pageRect.top
                        ) / pageRect.height(),

                        width = newSize / pageRect.width()
                    ).normalized()
                }

                lastTouchX = event.x
                lastTouchY = event.y

                calculateQrRect()

                if (dragMode == DragMode.MOVE) {
                    startQrLeft = qrRect.left
                    startQrTop = qrRect.top
                } else {
                    startWidthPx = qrRect.width()
                    startQrLeft = qrRect.left
                    startQrTop = qrRect.top
                }

                invalidate()

                onPlacementChanged?.invoke(placement)

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                val wasDragging = dragMode != DragMode.NONE

                dragMode = DragMode.NONE

                parent?.requestDisallowInterceptTouchEvent(false)

                return wasDragging
            }
        }

        return true
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
