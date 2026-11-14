package com.adityanile.expensetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExpenseAnalysisRecord(
    val id: Long,
    val savedAtMillis: Long,
    val fields: Map<String, String>,
    val partitionKey: String,
    val rowKey: String,
)

object ExpenseAnalysisStore {

    private const val PREF_NAME = "expense_analysis_store"
    private const val KEY_ITEMS = "items"

    fun saveRecord(context: Context, fields: Map<String, String>): ExpenseAnalysisRecord {
        val now = System.currentTimeMillis()
        val newRecord = ExpenseAnalysisRecord(
            id = now,
            savedAtMillis = now,
            fields = fields,
            partitionKey = partitionKeyFor(now),
            rowKey = rowKeyFor(now, now),
        )

        val existing = loadRecords(context).toMutableList()
        existing.add(0, newRecord)
        persistRecords(context, existing.take(MAX_RECORDS))

        return newRecord
    }

    fun loadRecords(context: Context): List<ExpenseAnalysisRecord> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
            ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val fieldsObject = item.optJSONObject("fields") ?: JSONObject()
                    val fields = linkedMapOf<String, String>()
                    val keys = fieldsObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        fields[key] = fieldsObject.optString(key)
                    }

                    val savedAt = item.optLong("savedAtMillis", 0L)
                    val localId = item.optLong("id", savedAt)
                    add(
                        ExpenseAnalysisRecord(
                            id = localId,
                            savedAtMillis = savedAt,
                            fields = fields,
                            partitionKey = item.optString("partitionKey", partitionKeyFor(savedAt)),
                            rowKey = item.optString("rowKey", rowKeyFor(savedAt, localId)),
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun deleteRecord(context: Context, localId: Long) {
        val updated = loadRecords(context).filterNot { it.id == localId }
        persistRecords(context, updated)
    }

    fun replaceAll(context: Context, records: List<ExpenseAnalysisRecord>) {
        persistRecords(context, records.take(MAX_RECORDS))
    }

    private fun persistRecords(context: Context, records: List<ExpenseAnalysisRecord>) {
        val payload = JSONArray()
        records.forEach { record ->
            payload.put(
                JSONObject()
                    .put("id", record.id)
                    .put("savedAtMillis", record.savedAtMillis)
                    .put("partitionKey", record.partitionKey)
                    .put("rowKey", record.rowKey)
                    .put("fields", JSONObject(record.fields)),
            )
        }

        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, payload.toString())
            .apply()
    }

    private fun partitionKeyFor(savedAtMillis: Long): String {
        return SimpleDateFormat("yyyyMM", Locale.US).format(Date(savedAtMillis))
    }

    private fun rowKeyFor(savedAtMillis: Long, localId: Long): String {
        return "$savedAtMillis-$localId"
    }

    private const val MAX_RECORDS = 200
}
