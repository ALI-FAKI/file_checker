
package com.ali.receiptshield.signature

import android.content.Context
import android.graphics.Bitmap
import java.io.File

class SignatureRepository(context: Context) {

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
}
