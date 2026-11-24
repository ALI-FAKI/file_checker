package com.ali.receiptshield.files

import com.ali.receiptshield.crypto.StampModeV4

enum class StampMode(
    val includesQr: Boolean,
    val includesSignature: Boolean,
    val displayName: String
) {
    QR_ONLY(
        includesQr = true,
        includesSignature = false,
        displayName = "QR Code Only"
    ),

    SIGNATURE_ONLY(
        includesQr = false,
        includesSignature = true,
        displayName = "Signature Only"
    ),

    QR_AND_SIGNATURE(
        includesQr = true,
        includesSignature = true,
        displayName = "QR Code + Signature"
    );

    fun toV4(): StampModeV4 = when (this) {
        QR_ONLY -> StampModeV4.QR_ONLY
        SIGNATURE_ONLY -> StampModeV4.SIGNATURE_ONLY
        QR_AND_SIGNATURE -> StampModeV4.QR_AND_SIGNATURE
    }

    companion object {
        fun fromName(value: String?): StampMode {
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true)
            } ?: QR_ONLY
        }

        /**
         * Maps a [StampModeV4] (crypto layer) to a [StampMode] (UI layer).
         *
         * EMBEDDED and DETACHED are QR-embedding strategies that the UI
         * does not differentiate yet, so they map to QR_ONLY. This is
         * still a meaningful distinction in the manifest because the
         * actual StampModeV4 value is preserved inside the payload.
         */
        fun fromV4(mode: StampModeV4): StampMode = when (mode) {
            StampModeV4.QR_ONLY          -> QR_ONLY
            StampModeV4.SIGNATURE_ONLY   -> SIGNATURE_ONLY
            StampModeV4.QR_AND_SIGNATURE -> QR_AND_SIGNATURE
            StampModeV4.EMBEDDED         -> QR_ONLY
            StampModeV4.DETACHED         -> QR_ONLY
        }
    }
}
