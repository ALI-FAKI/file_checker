package com.ali.receiptshield.qr

import com.ali.receiptshield.crypto.PlacementMetaV4

/**
 * Stores QR code placement relative to a document page.
 *
 * x and y represent the top-left position.
 * width represents the QR code width.
 *
 * All measurements are normalized between 0 and 1.
 */
data class QrPlacement(
    val pageIndex: Int = 0,
    val x: Float = 0.68f,
    val y: Float = 0.72f,
    val width: Float = 0.22f
) {
    fun normalized(): QrPlacement {
        val safeWidth = width.coerceIn(MIN_WIDTH, MAX_WIDTH)

        val safeX = x.coerceIn(0f, 1f - safeWidth)
        val safeY = y.coerceIn(0f, 1f - safeWidth)

        return copy(
            pageIndex = pageIndex.coerceAtLeast(0),
            x = safeX,
            y = safeY,
            width = safeWidth
        )
    }

    fun toPlacementMetaV4(): PlacementMetaV4 {
        val norm = normalized()
        return PlacementMetaV4(
            pageIndex = norm.pageIndex,
            xRatio = norm.x.toDouble(),
            yRatio = norm.y.toDouble(),
            widthRatio = norm.width.toDouble(),
            heightRatio = norm.width.toDouble()
        )
    }

    companion object {
        const val MIN_WIDTH = 0.06f
        const val MAX_WIDTH = 0.65f
    }
}
