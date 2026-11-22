
package com.ali.receiptshield.signature

import java.io.File

/**
 * Represents a signature prepared for insertion into a document.
 *
 * This model stores the signature image reference,
 * its document placement, and basic signing metadata.
 */
data class DocumentSignature(
    val signatureFile: File,
    val placement: SignaturePlacement = SignaturePlacement(),
    val signerName: String = "",
    val signingReason: String = "",
    val signingLocation: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {

    /**
     * Returns a normalized copy of the signature placement.
     */
    fun normalized(): DocumentSignature {
        return copy(
            placement = placement.normalized(),
            signerName = signerName.trim(),
            signingReason = signingReason.trim(),
            signingLocation = signingLocation.trim()
        )
    }

    /**
     * Checks whether the signature image is available.
     */
    fun isSignatureFileAvailable(): Boolean {
        return signatureFile.exists() &&
            signatureFile.isFile &&
            signatureFile.length() > 0
    }

    /**
     * Checks whether the signature can be prepared for document insertion.
     */
    fun isReadyForProcessing(): Boolean {
        return isSignatureFileAvailable() &&
            placement.pageIndex >= 0
    }
}
