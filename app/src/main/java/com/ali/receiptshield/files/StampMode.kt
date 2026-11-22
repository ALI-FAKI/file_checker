
package com.ali.receiptshield.files

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

    companion object {
        fun fromName(value: String?): StampMode {
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true)
            } ?: QR_ONLY
        }
    }
}
