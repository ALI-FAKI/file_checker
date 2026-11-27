package com.ali.receiptshield // Matches your ReceiptShield package

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FakiDiagnostics {
    @Volatile private var isInitialized = false
    @Volatile private var isHandlingCrash = false
    private lateinit var appContext: Context
    private val breadcrumbs = mutableListOf<String>()

    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        isInitialized = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (!isHandlingCrash) {
                isHandlingCrash = true
                try { saveCrashReport(thread, throwable) } catch (e: Exception) {}
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        breadcrumbs.add("[$time] $message")
        if (breadcrumbs.size > 20) breadcrumbs.removeAt(0)
    }

    private fun saveCrashReport(thread: Thread, throwable: Throwable) {
        var appVersion = "Unknown"
        try {
            val pInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            appVersion = pInfo.versionName ?: "Unknown"
        } catch (_: Exception) {}

        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("exceptionType", throwable.javaClass.name)
            put("exceptionMessage", throwable.message ?: "No message")
            put("stackTrace", Log.getStackTraceString(throwable))
            put("threadName", thread.name)
            put("packageName", appContext.packageName)
            put("appVersion", appVersion)
            put("osVersion", Build.VERSION.RELEASE)
            put("deviceModel", Build.MODEL)
            val crumbsJson = JSONArray()
            breadcrumbs.forEach { crumbsJson.put(it) }
            put("breadcrumbs", crumbsJson)
        }

        val crashDir = File(appContext.filesDir, "faki_crashes")
        if (!crashDir.exists()) crashDir.mkdirs()
        
        val fileName = "crash_${System.currentTimeMillis()}.json"
        File(crashDir, fileName).writeText(json.toString(2))
    }
}

class FakiCrashProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        val crashDir = File(context.filesDir, "faki_crashes")
        if (!crashDir.exists()) return null

        val cursor = MatrixCursor(arrayOf("id", "report_json"))
        crashDir.listFiles { file -> file.extension == "json" }?.forEach {
            cursor.addRow(arrayOf(it.name, it.readText()))
        }
        return cursor
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
