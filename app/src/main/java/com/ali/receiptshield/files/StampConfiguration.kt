package com.ali.receiptshield.files

import android.graphics.BitmapFactory
import com.ali.receiptshield.crypto.PlacementMetaV4
import com.ali.receiptshield.crypto.SignatureMetaV4
import com.ali.receiptshield.qr.QrPlacement
import com.ali.receiptshield.signature.DocumentSignature

data class StampConfiguration(
    val mode: StampMode = StampMode.QR_ONLY,
    val qrPlacement: QrPlacement = QrPlacement(),
    val signature: DocumentSignature? = null
) {

    /**
     * Returns an error message when the configuration
     * is incomplete, or null when it is ready.
     */
    fun validate(): String? {
        if (mode.includesSignature) {
            val documentSignature = signature
                ?: return "Please select or create a signature."

            if (!documentSignature.isSignatureFileAvailable()) {
                return "The signature image is missing or empty."
            }
        }

        if (mode.includesQr) {
            val placement = qrPlacement

            if (!placement.x.isFinite() ||
                !placement.y.isFinite() ||
                !placement.width.isFinite()
            ) {
                return "Invalid QR code placement."
            }

            if (placement.width <= 0f) {
                return "Invalid QR code size."
            }
        }

        return null
    }

    fun isValid(): Boolean {
        return validate() == null
    }

    fun normalized(): StampConfiguration {
        return copy(
            qrPlacement = qrPlacement.normalized(),
            signature = signature?.normalized()
        )
    }

    fun toQrPlacementMetaV4(): PlacementMetaV4? {
        if (!mode.includesQr) return null
        // FIXED: Use the QrPlacement extension for consistency
        return qrPlacement.toPlacementMetaV4()
    }

    fun toSignatureMetaV4(signatureDigestSha256: String): SignatureMetaV4? {
        if (!mode.includesSignature) return null
        val sig = signature?.normalized() ?: return null

        // FIXED: Compute aspect ratio from the signature file, then use
        // SignaturePlacement.toPlacementMetaV4() instead of sig.placement.height
        val aspectRatio = try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sig.signatureFile.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                opts.outHeight.toFloat() / opts.outWidth.toFloat()
            } else {
                0.35f
            }
        } catch (_: Exception) {
            0.35f
        }

        return SignatureMetaV4(
            placement = sig.placement.toPlacementMetaV4(aspectRatio),
            signatureDigestSha256 = signatureDigestSha256.lowercase().trim()
        )
    }
}
