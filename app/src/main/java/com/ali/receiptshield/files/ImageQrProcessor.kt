
package com.ali.receiptshield.files

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.ali.receiptshield.qr.QrPlacement

object ImageQrProcessor {

    /**
     * Adds a QR code to an image using normalized coordinates.
     * The original image remains unchanged.
     */
    @Throws(IOException::class)
    fun stampImageWithQrPlacement(
        originalImage: File,
        qrBitmap: Bitmap,
        placement: QrPlacement,
        outputFile: File
    ) {
        if (!originalImage.exists() || originalImage.length() <= 0L) {
            throw IOException("Original image is missing or empty.")
        }

        if (qrBitmap.isRecycled) {
            throw IOException("QR code bitmap is unavailable.")
        }

        val source = BitmapFactory.decodeFile(
            originalImage.absolutePath
        ) ?: throw IOException("Could not decode original image.")

        try {
            val result = source.copy(
                Bitmap.Config.ARGB_8888,
                true
            ) ?: throw IOException("Could not prepare output image.")

            try {
                val normalized = placement.normalized()

                val left = normalized.x * result.width
                val top = normalized.y * result.height

                val requestedSize =
                    normalized.width * result.width

                val availableWidth = result.width - left
                val availableHeight = result.height - top

                val qrSize = requestedSize.coerceAtMost(
                    minOf(availableWidth, availableHeight)
                )

                if (qrSize <= 0f) {
                    throw IOException(
                        "The selected QR position is outside the image."
                    )
                }

                val destination = RectF(
                    left,
                    top,
                    left + qrSize,
                    top + qrSize
                )

                val paint = Paint(
                    Paint.ANTI_ALIAS_FLAG or
                    Paint.FILTER_BITMAP_FLAG
                )

                Canvas(result).drawBitmap(
                    qrBitmap,
                    null,
                    destination,
                    paint
                )

                outputFile.parentFile?.mkdirs()

                FileOutputStream(outputFile).use { output ->
                    if (!result.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            output
                        )
                    ) {
                        throw IOException("Could not save QR-stamped image.")
                    }

                    output.flush()
                }
            } finally {
                result.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
