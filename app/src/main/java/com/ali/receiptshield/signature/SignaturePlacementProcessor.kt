package com.ali.receiptshield.signature

import android.graphics.Bitmap
import android.graphics.RectF
import com.ali.receiptshield.crypto.PlacementMetaV4
import kotlin.math.max

/**
 * Converts signature placement into document coordinates.
 *
 * Coordinates use the document page's dimensions.
 * The origin (0, 0) is the top-left corner.
 */
object SignaturePlacementProcessor {

    data class DocumentSignatureBounds(
        val pageIndex: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float
            get() = right - left

        val height: Float
            get() = bottom - top

        fun toRectF(): RectF {
            return RectF(left, top, right, bottom)
        }
    }

    /**
     * Calculates signature bounds using the page dimensions.
     *
     * The signature's original aspect ratio is preserved.
     */
    fun calculateBounds(
        placement: SignaturePlacement,
        pageWidth: Float,
        pageHeight: Float,
        signatureBitmap: Bitmap
    ): DocumentSignatureBounds {

        require(pageWidth > 0f) {
            "Page width must be greater than zero"
        }

        require(pageHeight > 0f) {
            "Page height must be greater than zero"
        }

        require(!signatureBitmap.isRecycled) {
            "Signature bitmap has been recycled"
        }

        require(signatureBitmap.width > 0 && signatureBitmap.height > 0) {
            "Signature bitmap has invalid dimensions"
        }

        val safePlacement = placement.normalized()

        val signatureWidth = pageWidth * safePlacement.width

        val aspectRatio =
            signatureBitmap.height.toFloat() /
                signatureBitmap.width.toFloat()

        val signatureHeight = signatureWidth * aspectRatio

        val left = safePlacement.x * pageWidth
        val top = safePlacement.y * pageHeight

        val safeLeft = left.coerceIn(
            0f,
            pageWidth - signatureWidth
        )

        val safeTop = top.coerceIn(
            0f,
            maxOf(0f, pageHeight - signatureHeight)
        )

        return DocumentSignatureBounds(
            pageIndex = safePlacement.pageIndex,
            left = safeLeft,
            top = safeTop,
            right = safeLeft + signatureWidth,
            bottom = safeTop + signatureHeight
        )
    }

    /**
     * Checks whether the calculated signature fits on the page.
     */
    fun fitsOnPage(
        bounds: DocumentSignatureBounds,
        pageWidth: Float,
        pageHeight: Float
    ): Boolean {

        return bounds.left >= 0f &&
            bounds.top >= 0f &&
            bounds.right <= pageWidth &&
            bounds.bottom <= pageHeight
    }

    /**
     * Converts computed signature bounds into normalized RS_V4 placement metadata.
     */
    fun toPlacementMetaV4(
        bounds: DocumentSignatureBounds,
        pageWidth: Float,
        pageHeight: Float
    ): PlacementMetaV4 {
        require(pageWidth > 0f) { "Page width must be greater than zero" }
        require(pageHeight > 0f) { "Page height must be greater than zero" }

        return PlacementMetaV4(
            pageIndex = bounds.pageIndex.coerceAtLeast(0),
            xRatio = (bounds.left / pageWidth).toDouble().coerceIn(0.0, 1.0),
            yRatio = (bounds.top / pageHeight).toDouble().coerceIn(0.0, 1.0),
            widthRatio = (bounds.width / pageWidth).toDouble().coerceIn(0.0, 1.0),
            heightRatio = (bounds.height / pageHeight).toDouble().coerceIn(0.0, 1.0)
        )
    }
}
