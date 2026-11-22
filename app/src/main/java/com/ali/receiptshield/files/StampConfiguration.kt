package com.ali.receiptshield.files

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
        val norm = qrPlacement.normalized()
        return PlacementMetaV4(
            pageIndex = norm.pageIndex,
            xRatio = norm.x.toDouble(),
            yRatio = norm.y.toDouble(),
            widthRatio = norm.width.toDouble(),
            heightRatio = norm.width.toDouble()
        )
    }

    fun toSignatureMetaV4(signatureDigestSha256: String): SignatureMetaV4? {
        if (!mode.includesSignature) return null
        val sig = signature?.normalized() ?: return null
        return SignatureMetaV4(
            placement = PlacementMetaV4(
                pageIndex = sig.placement.pageIndex,
                xRatio = sig.placement.x.toDouble(),
                yRatio = sig.placement.y.toDouble(),
                widthRatio = sig.placement.width.toDouble(),
                heightRatio = sig.placement.height.toDouble()
            ),
            signatureDigestSha256 = signatureDigestSha256.lowercase().trim()
        )
    }
}
