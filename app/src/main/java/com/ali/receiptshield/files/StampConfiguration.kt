
package com.ali.receiptshield.files

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
}
