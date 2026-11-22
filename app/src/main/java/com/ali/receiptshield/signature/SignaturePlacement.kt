package com.ali.receiptshield.signature

import com.ali.receiptshield.crypto.PlacementMetaV4

/**
 * Stores signature placement relative to a document page.
 *
 * x and y represent the top-left position.
 * width represents the signature width relative to the page width.
 *
 * The signature's original aspect ratio will be preserved
 * when it is displayed or inserted into a document.
 *
 * All measurements are normalized between 0 and 1.
 */
data class SignaturePlacement(
    val pageIndex: Int = 0,
    val x: Float = 0.12f,
    val y: Float = 0.78f,
    val width: Float = 0.32f
) {

    fun normalized(): SignaturePlacement {
        val safeWidth = width.coerceIn(MIN_WIDTH, MAX_WIDTH)

        val safeX = x.coerceIn(0f, 1f - safeWidth)
        val safeY = y.coerceIn(0f, 1f)

        return copy(
            pageIndex = pageIndex.coerceAtLeast(0),
            x = safeX,
            y = safeY,
            width = safeWidth
        )
    }

    fun toPlacementMetaV4(aspectRatio: Float = 0.35f): PlacementMetaV4 {
        val norm = normalized()
        val safeAspect = if (aspectRatio.isFinite() && aspectRatio > 0f) {
            aspectRatio.toDouble()
        } else {
            0.35
        }
        return PlacementMetaV4(
            pageIndex = norm.pageIndex,
            xRatio = norm.x.toDouble(),
            yRatio = norm.y.toDouble(),
            widthRatio = norm.width.toDouble(),
            heightRatio = (norm.width.toDouble() * safeAspect).coerceIn(0.01, 1.0)
        )
    }

    companion object {
        const val MIN_WIDTH = 0.08f
        const val MAX_WIDTH = 0.80f
    }
}
