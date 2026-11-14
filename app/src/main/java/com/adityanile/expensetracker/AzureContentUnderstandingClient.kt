package com.adityanile.expensetracker

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AzureContentUnderstandingClient {

    fun analyzeScreenshot(
        screenshotFile: File,
        settings: AzureAnalyzerSettings,
    ): Result<List<AzureExtractedField>> {
        return analyzeImageBytes(screenshotFile.readBytes(), settings)
    }

    fun analyzeImageBytes(
        imageBytes: ByteArray,
        settings: AzureAnalyzerSettings,
    ): Result<List<AzureExtractedField>> {
        if (!settings.isConfigured()) {
            return Result.failure(IllegalStateException("Azure analyzer settings are incomplete."))
        }

        return try {
            val encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val requestBody = JSONObject()
                .put(
                    "inputs",
                    JSONArray().put(
                        JSONObject().put("data", encoded),
                    ),
                )

            val requestUrl =
                "${settings.endpoint.trimEnd('/')}/contentunderstanding/analyzers/${settings.analyzerId}:analyze?api-version=${settings.apiVersion}"

            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Ocp-Apim-Subscription-Key", settings.apiKey)
                setRequestProperty("Content-Type", "application/json")
            }

            connection.outputStream.use { output ->
                output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val operationLocation = connection.getHeaderField("Operation-Location")
            val responseText = readBody(connection)
            connection.disconnect()

            when {
                responseCode in 200..299 && !operationLocation.isNullOrBlank() -> {
                    pollOperation(
                        operationLocation = resolveUrl(requestUrl, operationLocation),
                        settings = settings,
                    )
                }

                responseCode in 200..299 -> {
                    val payload = if (responseText.isNotBlank()) JSONObject(responseText) else JSONObject()
                    extractFields(payload)
                }

                else -> {
                    Result.failure(
                        IllegalStateException(
                            "Analyzer request failed ($responseCode): ${responseText.ifBlank { "No response body" }}",
                        ),
                    )
                }
            }
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun pollOperation(
        operationLocation: String,
        settings: AzureAnalyzerSettings,
    ): Result<List<AzureExtractedField>> {
        repeat(30) {
            Thread.sleep(1_000)

            val connection = (URL(operationLocation).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Ocp-Apim-Subscription-Key", settings.apiKey)
                setRequestProperty("Content-Type", "application/json")
            }

            val responseCode = connection.responseCode
            val responseText = readBody(connection)
            connection.disconnect()

            if (responseCode !in 200..299) {
                return Result.failure(
                    IllegalStateException(
                        "Analyzer polling failed ($responseCode): ${responseText.ifBlank { "No response body" }}",
                    ),
                )
            }

            val payload = if (responseText.isNotBlank()) JSONObject(responseText) else JSONObject()
            val status = payload.optString("status").lowercase()

            when (status) {
                "succeeded", "success", "completed" -> return extractFields(payload)
                "failed", "cancelled", "canceled" -> {
                    return Result.failure(
                        IllegalStateException("Analyzer operation ended with status '$status'."),
                    )
                }
            }
        }

        return Result.failure(IllegalStateException("Analyzer operation timed out."))
    }

    private fun extractFields(payload: JSONObject): Result<List<AzureExtractedField>> {
        val analysisPayload = payload.optJSONObject("result") ?: payload
        val fieldsContainer = analysisPayload
            .optJSONArray("contents")
            ?.optJSONObject(0)
            ?.optJSONObject("fields")
            ?: analysisPayload.optJSONObject("fields")

        if (fieldsContainer == null) {
            return Result.failure(IllegalStateException("No fields found in analyzer response."))
        }

        val extracted = mutableListOf<AzureExtractedField>()
        val keys = fieldsContainer.keys()
        while (keys.hasNext()) {
            val fieldName = keys.next()
            val fieldRawValue = fieldsContainer.opt(fieldName)
            val fieldObject = fieldRawValue as? JSONObject

            val displayValue = fieldObject?.let { unwrapFieldValue(it) } ?: normalizeValue(fieldRawValue)
            val confidence = fieldObject?.optDouble("confidence")?.takeUnless { it.isNaN() }

            extracted += AzureExtractedField(
                name = fieldName,
                value = displayValue,
                confidence = confidence,
            )
        }

        return Result.success(extracted.sortedBy { it.name.lowercase() })
    }

    private fun unwrapFieldValue(fieldObject: JSONObject): String {
        if (!fieldObject.isNull("value")) {
            return normalizeValue(fieldObject.opt("value"))
        }

        val fallbackKeys = listOf(
            "valueString",
            "valueNumber",
            "valueInteger",
            "valueBoolean",
            "valueDate",
            "valueDateTime",
            "text",
            "content",
        )

        for (candidate in fallbackKeys) {
            if (!fieldObject.isNull(candidate)) {
                return normalizeValue(fieldObject.opt(candidate))
            }
        }

        return fieldObject.toString()
    }

    private fun normalizeValue(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> ""
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString()
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        return stream.bufferedReader().use { it.readText() }
    }

    private fun resolveUrl(baseUrl: String, location: String): String {
        return try {
            URL(URL(baseUrl), location).toString()
        } catch (_: Throwable) {
            location
        }
    }
}

data class AzureExtractedField(
    val name: String,
    val value: String,
    val confidence: Double?,
)
