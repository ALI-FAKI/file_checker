
package com.ali.receiptshield.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ali.receiptshield.signature.DocumentSignature
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ImageSignatureProcessor {

    /**
     * Adds a signature to an image using normalized coordinates.
     * The original image remains unchanged.
     */
    @Throws(IOException::class)
    fun stampImageWithSignature(
        originalImage: File,
        signature: DocumentSignature,
        outputFile: File
    ) {
        if (!originalImage.exists() || originalImage.length() <= 0L) {
            throw IOException("Original image is missing or empty.")
        }

        if (!signature.isSignatureFileAvailable()) {
            throw IOException("Signature image is missing or empty.")
        }

        val source = BitmapFactory.decodeFile(
            originalImage.absolutePath
        ) ?: throw IOException("Could not decode original image.")

        val signatureBitmap = BitmapFactory.decodeFile(
            signature.signatureFile.absolutePath
        ) ?: run {
            source.recycle()
            throw IOException("Could not decode signature image.")
        }

        try {
            val placement = signature.placement.normalized()

            val result = source.copy(
                Bitmap.Config.ARGB_8888,
                true
            ) ?: throw IOException("Could not prepare output image.")

            try {
                val canvas = Canvas(result)

                val left = placement.x * result.width
                val top = placement.y * result.height

                val targetWidth = (
                    placement.width * result.width
                ).coerceAtMost(
                    result.width - left
                )

                val aspectRatio =
                    signatureBitmap.height.toFloat() /
                    signatureBitmap.width.toFloat()

                val targetHeight = (
                    targetWidth * aspectRatio
                ).coerceAtMost(
                    result.height - top
                )

                if (targetWidth <= 0f || targetHeight <= 0f) {
                    throw IOException(
                        "The selected signature position is outside the image."
                    )
                }

                val destination = RectF(
                    left,
                    top,
                    left + targetWidth,
                    top + targetHeight
                )

                val paint = Paint(
                    Paint.ANTI_ALIAS_FLAG or
                    Paint.FILTER_BITMAP_FLAG
                )

                canvas.drawBitmap(
                    signatureBitmap,
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
                        throw IOException("Could not save stamped image.")
                    }

                    output.flush()
                }
            } finally {
                result.recycle()
            }
        } finally {
            signatureBitmap.recycle()
            source.recycle()
        }
    }
}
