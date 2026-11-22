
package com.ali.receiptshield.signature

import android.graphics.BitmapFactory
import java.io.File

object SignatureValidation {

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )

    /**
     * Validates a signature before document processing.
     */
    fun validate(
        signature: DocumentSignature,
        totalPages: Int
    ): ValidationResult {

        val errors = mutableListOf<String>()

        if (totalPages <= 0) {
            errors.add("The document has no valid pages.")
        }

        if (signature.signerName.isBlank()) {
            errors.add("Signer name is required.")
        }

        val file = signature.signatureFile

        if (!file.exists()) {
            errors.add("Signature image file does not exist.")
        } else if (!file.isFile) {
            errors.add("Signature path is not a file.")
        } else if (file.length() <= 0L) {
            errors.add("Signature image file is empty.")
        } else {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFile(file.absolutePath, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                errors.add("Signature image is invalid or unreadable.")
            }
        }

        val placement = signature.placement.normalized()

        if (placement.pageIndex < 0 || placement.pageIndex >= totalPages) {
            errors.add("Selected signature page does not exist.")
        }

        if (!placement.x.isFinite() ||
            !placement.y.isFinite() ||
            !placement.width.isFinite()
        ) {
            errors.add("Signature placement contains invalid coordinates.")
        }

        if (placement.width !in
            SignaturePlacement.MIN_WIDTH..SignaturePlacement.MAX_WIDTH
        ) {
            errors.add("Signature width is outside the allowed range.")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    /**
     * Checks whether a signature image file is a valid image.
     */
    fun isValidImage(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            return false
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, options)

        return options.outWidth > 0 && options.outHeight > 0
    }
}
