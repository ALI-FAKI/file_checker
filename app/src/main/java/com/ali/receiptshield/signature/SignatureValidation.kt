package com.ali.receiptshield.signature

import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

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
     * Performs standard signature validation plus RS_V4 metadata checks
     * (finite height ratio and readable SHA-256 signature image digest).
     */
    fun validateForV4(
        signature: DocumentSignature,
        totalPages: Int
    ): ValidationResult {
        val base = validate(signature, totalPages)
        val errors = base.errors.toMutableList()

        if (!signature.placement.height.isFinite()) {
            errors.add("Signature height ratio is invalid.")
        }

        if (isValidImage(signature.signatureFile)) {
            val digest = computeSha256Hex(signature.signatureFile)
            if (digest.isNullOrBlank()) {
                errors.add("Unable to compute RS_V4 signature image digest.")
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors.distinct()
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

    private fun computeSha256Hex(file: File): String? {
        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
