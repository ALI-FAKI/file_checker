package com.ali.receiptshield.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object SignatureFileHelper {

    private const val SIGNATURE_DIRECTORY = "signatures"
    private const val FILE_PREFIX = "signature_"
    private const val FILE_EXTENSION = ".png"

    /**
     * Returns the application's private signature directory.
     */
    fun getSignatureDirectory(context: Context): File {
        val directory = File(
            context.filesDir,
            SIGNATURE_DIRECTORY
        )

        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create signature directory")
        }

        return directory
    }

    /**
     * Saves a signature bitmap as a PNG file.
     *
     * Returns the saved file.
     */
    @Throws(IOException::class)
    fun saveSignature(
        context: Context,
        bitmap: Bitmap,
        fileName: String? = null
    ): File {

        require(!bitmap.isRecycled) {
            "Cannot save a recycled bitmap"
        }

        val directory = getSignatureDirectory(context)

        val safeName = sanitizeFileName(
            fileName ?: "${FILE_PREFIX}${System.currentTimeMillis()}"
        )

        val finalName = if (safeName.endsWith(FILE_EXTENSION, ignoreCase = true)) {
            safeName.dropLast(FILE_EXTENSION.length) + FILE_EXTENSION
        } else {
            "$safeName$FILE_EXTENSION"
        }

        val outputFile = resolveInsideDirectory(directory, finalName)

        FileOutputStream(outputFile).use { output ->
            val success = bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                output
            )

            if (!success) {
                throw IOException("Failed to encode signature image")
            }

            output.flush()
        }

        return outputFile
    }

    /**
     * Loads a signature bitmap from a saved file.
     */
    fun loadSignature(file: File): Bitmap? {
        if (!file.exists() || !file.isFile) {
            return null
        }

        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * Loads a signature using its file name.
     */
    fun loadSignature(
        context: Context,
        fileName: String
    ): Bitmap? {

        val safeName = sanitizeFileName(fileName)

        val file = resolveInsideDirectory(
            getSignatureDirectory(context),
            safeName
        )

        return loadSignature(file)
    }

    /**
     * Returns all saved signature files.
     */
    fun getSavedSignatures(context: Context): List<File> {
        val directory = getSignatureDirectory(context)

        return directory.listFiles()
            ?.filter {
                it.isFile &&
                it.name.startsWith(FILE_PREFIX) &&
                it.extension.equals("png", ignoreCase = true)
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Deletes a saved signature.
     */
    fun deleteSignature(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }

        return file.delete()
    }

    /**
     * Deletes a saved signature using its file name.
     */
    fun deleteSignature(
        context: Context,
        fileName: String
    ): Boolean {

        val safeName = sanitizeFileName(fileName)

        val file = resolveInsideDirectory(
            getSignatureDirectory(context),
            safeName
        )

        return deleteSignature(file)
    }

    private fun resolveInsideDirectory(
        directory: File,
        fileName: String
    ): File {
        val target = File(directory, fileName)
        val canonicalDir = directory.canonicalFile
        val canonicalTarget = target.canonicalFile

        if (canonicalTarget.parentFile != canonicalDir) {
            throw IOException("Invalid signature file path")
        }

        return target
    }

    /**
     * Removes unsafe path characters from a file name.
     */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.')
            .take(100)

        return cleaned.ifBlank {
            "${FILE_PREFIX}${System.currentTimeMillis()}"
        }
    }
}
