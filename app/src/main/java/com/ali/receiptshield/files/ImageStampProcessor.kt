
package com.ali.receiptshield.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ali.receiptshield.files.StampConfiguration
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ImageStampProcessor {

    @Throws(IOException::class)
    fun stampImage(
        originalImage: File,
        qrBitmap: Bitmap?,
        configuration: StampConfiguration,
        outputFile: File
    ) {
        val validationError = configuration.validate()
        if (validationError != null) {
            throw IOException(validationError)
        }

        if (!originalImage.exists() || originalImage.length() <= 0L) {
            throw IOException("Original image is missing or empty.")
        }

        val config = configuration.normalized()

        if (config.mode.includesQr &&
            (qrBitmap == null || qrBitmap.isRecycled)
        ) {
            throw IOException("QR code bitmap is unavailable.")
        }

        val source = BitmapFactory.decodeFile(
            originalImage.absolutePath
        ) ?: throw IOException("Could not decode original image.")

        var signatureBitmap: Bitmap? = null

        try {
            if (config.mode.includesSignature) {
                val signatureFile = config.signature!!.signatureFile

                signatureBitmap = BitmapFactory.decodeFile(
                    signatureFile.absolutePath
                ) ?: throw IOException("Could not decode signature image.")
            }

            val result = source.copy(
                Bitmap.Config.ARGB_8888,
                true
            ) ?: throw IOException("Could not prepare output image.")

            try {
                val canvas = Canvas(result)

                val paint = Paint(
                    Paint.ANTI_ALIAS_FLAG or
                        Paint.FILTER_BITMAP_FLAG
                )

                // QR CODE
                if (config.mode.includesQr) {
                    val placement = config.qrPlacement

                    val left = placement.x * result.width
                    val top = placement.y * result.height

                    val requestedSize =
                        placement.width * result.width

                    val availableWidth = result.width - left
                    val availableHeight = result.height - top

                    val size = requestedSize.coerceAtMost(
                        minOf(availableWidth, availableHeight)
                    )

                    if (size <= 0f) {
                        throw IOException("Invalid QR code position.")
                    }

                    canvas.drawBitmap(
                        qrBitmap!!,
                        null,
                        RectF(
                            left,
                            top,
                            left + size,
                            top + size
                        ),
                        paint
                    )
                }

                // SIGNATURE
                if (config.mode.includesSignature) {
                    val signature = config.signature!!
                    val placement = signature.placement.normalized()
                    val bitmap = signatureBitmap!!

                    val left = placement.x * result.width
                    val top = placement.y * result.height

                    val requestedWidth =
                        placement.width * result.width

                    val availableWidth = result.width - left
                    val availableHeight = result.height - top

                    val aspectRatio =
                        bitmap.height.toFloat() / bitmap.width.toFloat()

                    var width = requestedWidth.coerceAtMost(
                        availableWidth
                    )

                    var height = width * aspectRatio

                    if (height > availableHeight) {
                        height = availableHeight
                        width = height / aspectRatio
                    }

                    if (width <= 0f || height <= 0f) {
                        throw IOException("Invalid signature position.")
                    }

                    canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(
                            left,
                            top,
                            left + width,
                            top + height
                        ),
                        paint
                    )
                }

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
            signatureBitmap?.recycle()
            source.recycle()
        }
    }
}
