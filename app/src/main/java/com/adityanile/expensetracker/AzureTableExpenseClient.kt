package com.adityanile.expensetracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AzureTableExpenseClient {

    private data class TableRequestResult(
        val responseCode: Int,
        val responseText: String,
    ) {
        val isSuccess: Boolean
            get() = responseCode in 200..299
    }

    fun upsertExpense(
        record: ExpenseAnalysisRecord,
        settings: AzureTableSettings,
    ): Result<Unit> {
        if (!settings.isConfigured()) {
            return Result.failure(IllegalStateException("Azure Table settings are incomplete."))
        }

        val payload = buildEntityPayload(record)

        val insertResult = requestWithBody(
            method = "POST",
            requestUrl = buildEntitiesUrl(settings),
            body = payload,
        )

        if (insertResult.isSuccess) {
            return Result.success(Unit)
        }

        if (insertResult.responseCode != HttpURLConnection.HTTP_CONFLICT) {
            return Result.failure(
                IllegalStateException(
                    "Azure Table insert failed (${insertResult.responseCode}): ${insertResult.responseText.ifBlank { "No response body" }}",
                ),
            )
        }

        val partitionKey = payload.optString("PartitionKey")
        val rowKey = payload.optString("RowKey")
        if (partitionKey.isBlank() || rowKey.isBlank()) {
            return Result.failure(IllegalStateException("Missing entity keys for update."))
        }

        val replaceUrl = buildSingleEntityUrl(
            settings = settings,
            partitionKey = partitionKey,
            rowKey = rowKey,
        )

        val replaceResult = requestWithBody(
            method = "PUT",
            requestUrl = replaceUrl,
            body = payload,
            ifMatch = "*",
        )

        return if (replaceResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "Azure Table update failed (${replaceResult.responseCode}): ${replaceResult.responseText.ifBlank { "No response body" }}",
                ),
            )
        }
    }

    fun fetchExpenses(settings: AzureTableSettings): Result<List<ExpenseAnalysisRecord>> {
        if (!settings.isConfigured()) {
            return Result.failure(IllegalStateException("Azure Table settings are incomplete."))
        }

        return try {
            val connection = (URL(buildEntitiesUrl(settings)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json;odata=nometadata")
                setRequestProperty("x-ms-date", rfc1123Now())
                setRequestProperty("x-ms-version", "2023-11-03")
            }

            val responseCode = connection.responseCode
            val responseText = readBody(connection)
            connection.disconnect()

            if (responseCode !in 200..299) {
                return Result.failure(
                    IllegalStateException(
                        "Azure Table fetch failed ($responseCode): ${responseText.ifBlank { "No response body" }}",
                    ),
                )
            }

            val payload = JSONObject(responseText.ifBlank { "{}" })
            val valueArray = payload.optJSONArray("value") ?: return Result.success(emptyList())
            val records = buildList {
                for (index in 0 until valueArray.length()) {
                    val item = valueArray.optJSONObject(index) ?: continue
                    val partitionKey = item.optString("PartitionKey")
                    val rowKey = item.optString("RowKey")
                    val savedAtMillis = item.optLong("SavedAtMillis", 0L)
                    val localId = item.optLong("LocalId", savedAtMillis)

                    val fields = linkedMapOf<String, String>()
                    val keys = item.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key in RESERVED_KEYS || ExpenseFieldRules.isExcludedFieldKey(key)) continue
                        val raw = item.opt(key)
                        fields[key] = normalizeFieldValue(key, normalizeValue(raw))
                    }

                    add(
                        ExpenseAnalysisRecord(
                            id = localId,
                            savedAtMillis = savedAtMillis,
                            fields = fields,
                            partitionKey = partitionKey,
                            rowKey = rowKey,
                        ),
                    )
                }
            }

            Result.success(records)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    fun deleteExpense(
        record: ExpenseAnalysisRecord,
        settings: AzureTableSettings,
    ): Result<Unit> {
        if (!settings.isConfigured()) {
            return Result.failure(IllegalStateException("Azure Table settings are incomplete."))
        }

        if (record.partitionKey.isBlank() || record.rowKey.isBlank()) {
            return Result.failure(IllegalStateException("Missing Azure keys for delete."))
        }

        val entityUrl = buildSingleEntityUrl(
            settings = settings,
            partitionKey = record.partitionKey,
            rowKey = record.rowKey,
        )

        return try {
            val connection = (URL(entityUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json;odata=nometadata")
                setRequestProperty("x-ms-date", rfc1123Now())
                setRequestProperty("x-ms-version", "2023-11-03")
                setRequestProperty("If-Match", "*")
            }

            val responseCode = connection.responseCode
            val responseText = readBody(connection)
            connection.disconnect()

            if (responseCode in 200..299 || responseCode == 404) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IllegalStateException(
                        "Azure Table delete failed ($responseCode): ${responseText.ifBlank { "No response body" }}",
                    ),
                )
            }
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun requestWithBody(
        method: String,
        requestUrl: String,
        body: JSONObject,
        ifMatch: String? = null,
    ): TableRequestResult {
        return try {
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Accept", "application/json;odata=nometadata")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-ms-date", rfc1123Now())
                setRequestProperty("x-ms-version", "2023-11-03")
                if (!ifMatch.isNullOrBlank()) {
                    setRequestProperty("If-Match", ifMatch)
                }
            }

            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = readBody(connection)
            connection.disconnect()
            TableRequestResult(responseCode = responseCode, responseText = responseText)
        } catch (error: Throwable) {
            TableRequestResult(
                responseCode = -1,
                responseText = error.message ?: "Unexpected request error",
            )
        }
    }

    private fun buildEntityPayload(record: ExpenseAnalysisRecord): JSONObject {
        val partitionKey = if (record.partitionKey.isNotBlank()) {
            record.partitionKey
        } else {
            SimpleDateFormat("yyyyMM", Locale.US).format(Date(record.savedAtMillis))
        }

        val rowKey = if (record.rowKey.isNotBlank()) {
            record.rowKey
        } else {
            "${record.savedAtMillis}-${record.id}"
        }

        return JSONObject().apply {
            put("PartitionKey", partitionKey)
            put("RowKey", rowKey)
            put("SavedAtMillis", record.savedAtMillis)
            put("LocalId", record.id)
            record.fields.forEach { (key, value) ->
                if (ExpenseFieldRules.isExcludedFieldKey(key)) return@forEach
                put(sanitizePropertyName(key), normalizeFieldValue(key, value))
            }
        }
    }

    private fun buildEntitiesUrl(settings: AzureTableSettings): String {
        val base = settings.endpoint.trim().trimEnd('/')
        val token = settings.sasToken.trim().removePrefix("?")
        return "$base/${settings.tableName}()?$token"
    }

    private fun buildSingleEntityUrl(
        settings: AzureTableSettings,
        partitionKey: String,
        rowKey: String,
    ): String {
        val escapedPartition = partitionKey.replace("'", "''")
        val escapedRow = rowKey.replace("'", "''")
        val keyExpr = "PartitionKey='${urlEncode(escapedPartition)}',RowKey='${urlEncode(escapedRow)}'"
        val base = settings.endpoint.trim().trimEnd('/')
        val token = settings.sasToken.trim().removePrefix("?")
        return "$base/${settings.tableName}($keyExpr)?$token"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private fun sanitizePropertyName(name: String): String {
        val sanitized = name
            .replace(Regex("[^A-Za-z0-9_]"), "_")
            .trim('_')
            .ifBlank { "Field" }

        return if (sanitized.first().isDigit()) {
            "F_$sanitized"
        } else {
            sanitized
        }
    }

    private fun normalizeValue(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> ""
            else -> value.toString()
        }
    }

    private fun normalizeFieldValue(key: String, value: String): String {
        if (ExpenseFieldRules.isExcludedFieldKey(key)) return ""
        if (!isPaymentDateTimeKey(key)) return value
        return normalizePaymentDateTime(value)
    }

    private fun isPaymentDateTimeKey(key: String): Boolean {
        val normalized = key.replace("_", "")
        return normalized.equals("PaymentDateTime", ignoreCase = true)
    }

    private fun normalizePaymentDateTime(input: String): String {
        val raw = input.trim()
        if (raw.isBlank()) return raw

        val normalizedInput = raw
            .replace(Regex("(?i)\\bon\\b"), " ")
            .replace(Regex("(?i)sept"), "Sep")
            .replace(Regex("\\s+"), " ")
            .trim()

        val patterns = listOf(
            "dd MMM yyyy, hh:mm a",
            "hh:mm a dd MMM yyyy",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd HH:mm",
            "dd-MM-yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy hh:mm a",
            "dd MMM yyyy, hh:mm:ss a",
            "dd MMM yyyy hh:mm a",
            "MMM dd, yyyy hh:mm a",
        )

        val parsedDate = patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    isLenient = false
                }.parse(normalizedInput)
            }.getOrNull()
        } ?: return raw

        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(parsedDate)
    }

    private fun rfc1123Now(): String {
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("GMT")
        return formatter.format(Date())
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        return stream.bufferedReader().use { it.readText() }
    }

    private val RESERVED_KEYS = setOf(
        "PartitionKey",
        "RowKey",
        "Timestamp",
        "SavedAtMillis",
        "LocalId",
        "odata.etag",
    )
}
