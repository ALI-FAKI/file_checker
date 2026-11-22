package com.ali.receiptshield.signature

import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.crypto.SignatureMetaV4
import java.io.File
import java.io.IOException

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

    /**
     * Builds an RS_V4 SignatureMetaV4 binding the signature placement
     * and the SHA-256 digest of the signature image bytes.
     */
    @Throws(IOException::class)
    fun toSignatureMetaV4(
        securityManager: ReceiptSecurityManager = ReceiptSecurityManager(),
        aspectRatio: Float = 0.35f
    ): SignatureMetaV4 {
        if (!isSignatureFileAvailable()) {
            throw IOException("Signature file is missing or empty.")
        }
        val digest = securityManager.sha256Bytes(signatureFile.readBytes())
        return SignatureMetaV4(
            placement = placement.toPlacementMetaV4(aspectRatio = aspectRatio),
            signatureDigestSha256 = digest
        )
    }
}
