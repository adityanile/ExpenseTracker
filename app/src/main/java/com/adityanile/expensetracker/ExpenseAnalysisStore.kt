package com.adityanile.expensetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ExpenseAnalysisRecord(
    val id: Long,
    val savedAtMillis: Long,
    val fields: Map<String, String>,
)

object ExpenseAnalysisStore {

    private const val PREF_NAME = "expense_analysis_store"
    private const val KEY_ITEMS = "items"

    fun saveRecord(context: Context, fields: Map<String, String>) {
        val existing = loadRecords(context).toMutableList()
        existing.add(
            0,
            ExpenseAnalysisRecord(
                id = System.currentTimeMillis(),
                savedAtMillis = System.currentTimeMillis(),
                fields = fields,
            ),
        )

        val payload = JSONArray()
        existing.take(MAX_RECORDS).forEach { record ->
            payload.put(
                JSONObject()
                    .put("id", record.id)
                    .put("savedAtMillis", record.savedAtMillis)
                    .put("fields", JSONObject(record.fields)),
            )
        }

        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, payload.toString())
            .apply()
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
                    add(
                        ExpenseAnalysisRecord(
                            id = item.optLong("id", 0L),
                            savedAtMillis = item.optLong("savedAtMillis", 0L),
                            fields = fields,
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private const val MAX_RECORDS = 200
}
