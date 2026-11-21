package com.ali.receiptshield.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StampedRecord(
    val displayName: String,
    val outputFilePath: String,
    val mimeType: String,
    val isDocumentStamp: Boolean,
    val hash: String,
    val issuerId: String,
    val timestamp: Long,
    val payloadSerialized: String
)

class StampRepository(context: Context) {

    private val prefs = context.getSharedPreferences("receiptshield_prefs", Context.MODE_PRIVATE)

    fun getIssuerId(): String =
        prefs.getString("issuer_id", "SHOP_ZANZIBAR_01") ?: "SHOP_ZANZIBAR_01"

    fun saveIssuerId(issuerId: String): String {
        val clean = issuerId.trim().ifEmpty { "SHOP_ZANZIBAR_01" }
        prefs.edit().putString("issuer_id", clean).apply()
        return clean
    }

    fun saveRecord(record: StampedRecord) {
        val currentList = loadRecords().toMutableList()
        currentList.add(0, record)
        val trimmed = currentList.take(50)

        val array = JSONArray()
        for (item in trimmed) {
            array.put(JSONObject().apply {
                put("displayName", item.displayName)
                put("outputFilePath", item.outputFilePath)
                put("mimeType", item.mimeType)
                put("isDocumentStamp", item.isDocumentStamp)
                put("hash", item.hash)
                put("issuerId", item.issuerId)
                put("timestamp", item.timestamp)
                put("payloadSerialized", item.payloadSerialized)
            })
        }
        prefs.edit().putString("stamped_records", array.toString()).apply()
    }

    fun loadRecords(): List<StampedRecord> {
        val raw = prefs.getString("stamped_records", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<StampedRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    StampedRecord(
                        displayName = obj.optString("displayName", "Unknown"),
                        outputFilePath = obj.optString("outputFilePath", ""),
                        mimeType = obj.optString("mimeType", "*/*"),
                        isDocumentStamp = obj.optBoolean("isDocumentStamp", false),
                        hash = obj.optString("hash", ""),
                        issuerId = obj.optString("issuerId", "SHOP_ZANZIBAR_01"),
                        timestamp = obj.optLong("timestamp", 0L),
                        payloadSerialized = obj.optString("payloadSerialized", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun findRecordByHash(hash: String): StampedRecord? {
        if (hash.isBlank()) return null
        return loadRecords().firstOrNull { it.hash.equals(hash, ignoreCase = true) }
    }

    fun clearRecords() {
        prefs.edit().remove("stamped_records").apply()
    }
}
