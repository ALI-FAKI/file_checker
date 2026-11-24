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
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.crypto.RsV4IntegrityManager
import com.ali.receiptshield.crypto.SignatureMetaV4
import com.ali.receiptshield.crypto.StampModeV4
import com.ali.receiptshield.signature.DocumentSignature
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ImageSignatureProcessor {

    @Throws(IOException::class)
    fun stampImageWithSignatureV4(
        originalImage: File,
        signature: DocumentSignature,
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
        if (!signature.isSignatureFileAvailable()) {
            throw IOException("Signature image is missing or empty.")
        }

        val tempStampedFile = File.createTempFile(
            "rs_v4_img_sig_",
            ".png",
            outputFile.parentFile ?: originalImage.parentFile
        )

        try {
            stampImageWithSignature(
                originalImage = originalImage,
                signature = signature,
                outputFile = tempStampedFile
            )

            val stampedBytes = tempStampedFile.readBytes()
            val stampedDigest = securityManager.sha256Bytes(stampedBytes)
            val sigDigest = securityManager.sha256Bytes(signature.signatureFile.readBytes())

            val detectedFormat = DocumentFormatV4.fromFileNameOrMime(originalImage.name, null)
                ?: DocumentFormatV4.PNG

            // FIXED: Compute aspect ratio from signature image, then use toPlacementMetaV4()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(signature.signatureFile.absolutePath, opts)
            val aspectRatio = if (opts.outWidth > 0 && opts.outHeight > 0) {
                opts.outHeight.toFloat() / opts.outWidth.toFloat()
            } else {
                0.35f
            }

            val signatureMeta = SignatureMetaV4(
                placement = signature.placement.toPlacementMetaV4(aspectRatio),
                signatureDigestSha256 = sigDigest
            )

            val manifest = integrityManager.buildManifest(
                receiptId = receiptId,
                documentFormat = detectedFormat,
                stampMode = StampModeV4.SIGNATURE_ONLY,
                originalDocSha256 = originalDocSha256,
                issuerId = issuerId,
                qrPlacement = null,
                signatureMeta = signatureMeta,
                injectedArtifacts = listOf(
                    InjectedArtifactV4(
                        type = InjectedArtifactType.METADATA_STREAM,
                        identifier = "image_signature_stamped_png",
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
