package com.ali.receiptshield.signature

import android.content.Context
import android.graphics.Bitmap
import com.ali.receiptshield.crypto.ReceiptSecurityManager
import com.ali.receiptshield.crypto.SignatureMetaV4
import java.io.File
import java.io.IOException

class SignatureRepository(
    context: Context,
    private val securityManager: ReceiptSecurityManager = ReceiptSecurityManager()
) {

    private val appContext = context.applicationContext

    /**
     * Saves a signature permanently in private app storage.
     */
    fun save(
        bitmap: Bitmap,
        name: String? = null
    ): File {
        return SignatureFileHelper.saveSignature(
            context = appContext,
            bitmap = bitmap,
            fileName = name
        )
    }

    /**
     * Loads a signature from a saved file.
     */
    fun load(file: File) = SignatureFileHelper.loadSignature(file)

    /**
     * Loads a signature using its file name.
     */
    fun loadByName(fileName: String) =
        SignatureFileHelper.loadSignature(
            context = appContext,
            fileName = fileName
        )

    /**
     * Returns all saved signatures, newest first.
     */
    fun getAll(): List<File> {
        return SignatureFileHelper.getSavedSignatures(appContext)
    }

    /**
     * Deletes a saved signature.
     */
    fun delete(file: File): Boolean {
        return SignatureFileHelper.deleteSignature(file)
    }

    /**
     * Deletes a saved signature by file name.
     */
    fun deleteByName(fileName: String): Boolean {
        return SignatureFileHelper.deleteSignature(
            context = appContext,
            fileName = fileName
        )
    }

    /**
     * Checks whether a signature file exists.
     */
    fun exists(fileName: String): Boolean {
        return getAll().any { it.name == fileName }
    }

    /**
     * Computes the lowercase SHA-256 hex digest of a saved signature image file
     * for RS_V4 SignatureMetaV4 binding.
     */
    @Throws(IOException::class)
    fun computeSignatureDigestSha256(file: File): String {
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            throw IOException("Signature file is missing or empty.")
        }
        return securityManager.sha256Bytes(file.readBytes())
    }

    /**
     * Builds an RS_V4 SignatureMetaV4 from a DocumentSignature and its file digest.
     */
    @Throws(IOException::class)
    fun buildSignatureMetaV4(signature: DocumentSignature): SignatureMetaV4 {
        val digest = computeSignatureDigestSha256(signature.signatureFile)
        return SignatureMetaV4(
            placement = signature.placement.toPlacementMetaV4(),
            signatureDigestSha256 = digest
        )
    }
}
