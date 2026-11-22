package com.ali.receiptshield.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ali.receiptshield.crypto.DocumentFormatV4
import com.ali.receiptshield.crypto.InjectedArtifactType
import com.ali.receiptshield.crypto.InjectedArtifactV4
import com.ali.receiptshield.crypto.IntegrityManifestV4
import com.ali.receiptshield.crypto.PlacementMetaV4
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.crypto.RsV4IntegrityManager
import com.ali.receiptshield.crypto.StampModeV4
import com.ali.receiptshield.qr.QrPlacement
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ImageQrProcessor {

    @Throws(IOException::class)
    fun stampImageWithQrPlacementV4(
        originalImage: File,
        qrBitmap: Bitmap,
        placement: QrPlacement,
        receiptId: String,
        originalDocSha256: String,
        issuerId: String,
        outputFile: File,
        securityManager: ReceiptSecurityManager = ReceiptSecurityManager(),
        integrityManager: RsV4IntegrityManager = RsV4IntegrityManager(securityManager)
    ): IntegrityManifestV4 {
        if (!originalImage.exists() || originalImage.length() <= 0L) {
            throw IOException("Original image is missing or empty.")
        }

        if (qrBitmap.isRecycled) {
            throw IOException("QR code bitmap is unavailable.")
        }

        val tempStampedFile = File.createTempFile(
            "rs_v4_img_qr_",
            ".png",
            outputFile.parentFile ?: originalImage.parentFile
        )

        try {
            stampImageWithQrPlacement(
                originalImage = originalImage,
                qrBitmap = qrBitmap,
                placement = placement,
                outputFile = tempStampedFile
            )

            val stampedBytes = tempStampedFile.readBytes()
            val stampedDigest = securityManager.sha256Bytes(stampedBytes)
            val norm = placement.normalized()

            val detectedFormat = DocumentFormatV4.fromFileNameOrMime(originalImage.name, null)
                ?: DocumentFormatV4.PNG

            val qrPlacementMeta = PlacementMetaV4(
                pageIndex = norm.pageIndex,
                xRatio = norm.x.toDouble(),
                yRatio = norm.y.toDouble(),
                widthRatio = norm.width.toDouble(),
                heightRatio = norm.width.toDouble()
            )

            val manifest = integrityManager.buildManifest(
                receiptId = receiptId,
                documentFormat = detectedFormat,
                stampMode = StampModeV4.QR_ONLY,
                originalDocSha256 = originalDocSha256,
                issuerId = issuerId,
                qrPlacement = qrPlacementMeta,
                signatureMeta = null,
                injectedArtifacts = listOf(
                    InjectedArtifactV4(
                        type = InjectedArtifactType.METADATA_STREAM,
                        identifier = "image_qr_stamped_png",
                        digestSha256 = stampedDigest
                    )
                )
            )

            val finalBytes = integrityManager.embedManifestInImageBytes(stampedBytes, manifest)
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(finalBytes)
            return manifest
        } finally {
            if (tempStampedFile.exists()) {
                tempStampedFile.delete()
            }
        }
    }

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
