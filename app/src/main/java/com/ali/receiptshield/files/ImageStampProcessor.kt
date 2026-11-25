
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
import com.ali.receiptshield.crypto.StampModeV4
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ImageStampProcessor {

    @Throws(IOException::class)
    fun stampImageV4(
        originalImage: File,
        qrBitmap: Bitmap?,
        configuration: StampConfiguration,
        receiptId: String,
        originalDocSha256: String,
        issuerId: String,
        outputFile: File,
        securityManager: ReceiptSecurityManager = ReceiptSecurityManager(),
        integrityManager: RsV4IntegrityManager = RsV4IntegrityManager(securityManager)
    ): IntegrityManifestV4 {

        val validationError = configuration.validate()
        if (validationError != null) {
            throw IOException(validationError)
        }

        if (!originalImage.exists() || originalImage.length() <= 0L) {
            throw IOException("Original image is missing or empty.")
        }

        val config = configuration.normalized()

        val tempStampedFile = File.createTempFile(
            "rs_v4_img_",
            ".png",
            outputFile.parentFile ?: originalImage.parentFile
        )

        try {
            // Create the stamped image first.
            stampImage(
                originalImage = originalImage,
                qrBitmap = qrBitmap,
                configuration = config,
                outputFile = tempStampedFile
            )

            // Calculate the hash of the completed stamped image.
            val stampedBytes = tempStampedFile.readBytes()

            if (stampedBytes.isEmpty()) {
                throw IOException("Stamped image is empty.")
            }

            val stampedDigest =
                securityManager.sha256Bytes(stampedBytes)

            // Determine the original image format.
            val detectedFormat =
                DocumentFormatV4.fromFileNameOrMime(
                    originalImage.name,
                    null
                ) ?: DocumentFormatV4.PNG

            // Resolve the stamping mode.
            val stampMode = when {
                config.mode.includesQr &&
                    config.mode.includesSignature ->
                    StampModeV4.QR_AND_SIGNATURE

                config.mode.includesSignature ->
                    StampModeV4.SIGNATURE_ONLY

                else ->
                    StampModeV4.QR_ONLY
            }

            // Prepare placement metadata.
            val qrPlacementMeta =
                config.toQrPlacementMetaV4()

            // Prepare signature metadata when a signature is included.
            val signatureMeta =
                if (config.mode.includesSignature) {
                    val signature = config.signature
                        ?: throw IOException(
                            "Signature configuration is missing."
                        )

                    if (
                        !signature.isSignatureFileAvailable()
                    ) {
                        throw IOException(
                            "Signature image is missing or empty."
                        )
                    }

                    val signatureDigest =
                        securityManager.sha256Bytes(
                            signature.signatureFile.readBytes()
                        )

                    config.toSignatureMetaV4(
                        signatureDigest
                    )
                } else {
                    null
                }

            // Build the final manifest with the stamped-image artifact.
            val manifest = integrityManager.buildManifest(
                receiptId = receiptId,
                documentFormat = detectedFormat,
                stampMode = stampMode,
                originalDocSha256 = originalDocSha256,
                issuerId = issuerId,
                qrPlacement = qrPlacementMeta,
                signatureMeta = signatureMeta,
                injectedArtifacts = listOf(
                    InjectedArtifactV4(
                        type = InjectedArtifactType.METADATA_STREAM,
                        identifier = "image_stamped_png",
                        digestSha256 = stampedDigest
                    )
                )
            )

            // Sign the completed manifest after all artifact hashes are known.
            val signedManifest =
                integrityManager.signCompletedManifest(manifest)

            // Embed the signed manifest into the image.
            val finalBytes =
                integrityManager.embedManifestInImageBytes(
                    stampedBytes,
                    signedManifest
                )

            outputFile.parentFile?.mkdirs()

            if (!outputFile.parentFile?.exists().orFalse()) {
                throw IOException(
                    "Could not create output directory."
                )
            }

            outputFile.writeBytes(finalBytes)

            return signedManifest

        } finally {
            if (tempStampedFile.exists()) {
                tempStampedFile.delete()
            }
        }
    }

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

        if (
            config.mode.includesQr &&
            (qrBitmap == null || qrBitmap.isRecycled)
        ) {
            throw IOException("QR code bitmap is unavailable.")
        }

        val source = BitmapFactory.decodeFile(
            originalImage.absolutePath
        ) ?: throw IOException(
            "Could not decode original image."
        )

        var signatureBitmap: Bitmap? = null

        try {
            // Load the signature when required.
            if (config.mode.includesSignature) {
                val signatureFile = config.signature!!.signatureFile

                if (
                    !signatureFile.exists() ||
                    signatureFile.length() <= 0L
                ) {
                    throw IOException(
                        "Signature image is missing or empty."
                    )
                }

                signatureBitmap = BitmapFactory.decodeFile(
                    signatureFile.absolutePath
                ) ?: throw IOException(
                    "Could not decode signature image."
                )
            }

            val result = source.copy(
                Bitmap.Config.ARGB_8888,
                true
            ) ?: throw IOException(
                "Could not prepare output image."
            )

            try {
                val canvas = Canvas(result)

                val paint = Paint(
                    Paint.ANTI_ALIAS_FLAG or
                        Paint.FILTER_BITMAP_FLAG
                )

                // -------------------------------------------------
                // QR CODE
                // -------------------------------------------------

                if (config.mode.includesQr) {
                    val placement = config.qrPlacement

                    val left = placement.x * result.width
                    val top = placement.y * result.height

                    val requestedSize =
                        placement.width * result.width

                    val availableWidth =
                        result.width - left

                    val availableHeight =
                        result.height - top

                    val size = requestedSize.coerceAtMost(
                        minOf(
                            availableWidth,
                            availableHeight
                        )
                    )

                    if (size <= 0f) {
                        throw IOException(
                            "Invalid QR code position."
                        )
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

                // -------------------------------------------------
                // SIGNATURE
                // -------------------------------------------------

                if (config.mode.includesSignature) {
                    val signature = config.signature!!
                    val placement =
                        signature.placement.normalized()

                    val bitmap = signatureBitmap!!

                    val left = placement.x * result.width
                    val top = placement.y * result.height

                    val requestedWidth =
                        placement.width * result.width

                    val availableWidth =
                        result.width - left

                    val availableHeight =
                        result.height - top

                    val aspectRatio =
                        bitmap.height.toFloat() /
                            bitmap.width.toFloat()

                    var width = requestedWidth.coerceAtMost(
                        availableWidth
                    )

                    var height = width * aspectRatio

                    if (height > availableHeight) {
                        height = availableHeight
                        width = height / aspectRatio
                    }

                    if (width <= 0f || height <= 0f) {
                        throw IOException(
                            "Invalid signature position."
                        )
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

                // -------------------------------------------------
                // SAVE STAMPED IMAGE
                // -------------------------------------------------

                outputFile.parentFile?.mkdirs()

                FileOutputStream(outputFile).use { output ->
                    if (
                        !result.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            output
                        )
                    ) {
                        throw IOException(
                            "Could not save stamped image."
                        )
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

    private fun Boolean?.orFalse(): Boolean {
        return this ?: false
    }
}
