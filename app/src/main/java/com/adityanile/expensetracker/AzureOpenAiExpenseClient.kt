package com.adityanile.expensetracker

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AzureOpenAiExpenseClient {

    fun analyzeScreenshot(
        screenshotFile: File,
        settings: AzureOpenAiSettings,
    ): Result<List<AzureExtractedField>> {
        return analyzeImageBytes(screenshotFile.readBytes(), settings)
    }

    fun analyzeImageBytes(
        imageBytes: ByteArray,
        settings: AzureOpenAiSettings,
    ): Result<List<AzureExtractedField>> {
        if (!settings.isConfigured()) {
            return Result.failure(IllegalStateException("Azure OpenAI settings are incomplete."))
        }

        return try {
            val encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val dataUrl = "data:image/png;base64,$encoded"
            val requestBody = buildRequestBody(
                modelDeployment = settings.modelDeployment,
                imageDataUrl = dataUrl,
            )

            val requestUrl = resolveResponsesUrl(settings.endpoint)

            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("api-key", settings.apiKey)
                setRequestProperty("Content-Type", "application/json")
            }

            connection.outputStream.use { output ->
                output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = readBody(connection)
            connection.disconnect()

            if (responseCode !in 200..299) {
                return Result.failure(
                    IllegalStateException(
                        "Azure OpenAI request failed ($responseCode): ${responseText.ifBlank { "No response body" }}",
                    ),
                )
            }

            val payload = JSONObject(responseText.ifBlank { "{}" })
            extractFieldsFromResponse(payload)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun buildRequestBody(
        modelDeployment: String,
        imageDataUrl: String,
    ): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("Amount", JSONObject().put("type", "number"))
                    .put("MerchantName", JSONObject().put("type", "string"))
                    .put("ExpenseCategory", JSONObject().put("type", "string"))
                    .put("PaymentDateTime", JSONObject().put("type", "string"))
                    .put("ItemDescription", JSONObject().put("type", "string"))
                    .put("UpiTransactionId", JSONObject().put("type", "string")),
            )
            .put(
                "required",
                JSONArray()
                    .put("Amount")
                    .put("MerchantName")
                    .put("ExpenseCategory")
                    .put("PaymentDateTime")
                    .put("ItemDescription")
                    .put("UpiTransactionId"),
            )
            .put("additionalProperties", false)

        val userPrompt = """
            Extract expense details from this payment screenshot.
            Return strict JSON with:
            1) Amount (number only)
            2) MerchantName
            3) ExpenseCategory (single word)
            4) PaymentDateTime (exact format: yyyy-MM-dd HH:mm:ss. Example: 2026-11-14 13:32:00)
            5) ItemDescription (short description from context)
            6) UpiTransactionId

            If any value is missing, return:
            - Amount: 0
            - string fields: "Unknown"
        """.trimIndent()

        return JSONObject()
            .put("model", modelDeployment)
            .put(
                "input",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_text")
                                        .put("text", "You are an expense extraction assistant. Return only valid JSON that follows the schema."),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "input_text")
                                            .put("text", userPrompt),
                                    )
                                    .put(
                                        JSONObject()
                                            .put("type", "input_image")
                                            .put("image_url", imageDataUrl),
                                    ),
                            ),
                    ),
            )
            .put(
                "text",
                JSONObject().put(
                    "format",
                    JSONObject()
                        .put("type", "json_schema")
                        .put("name", "ExpenseExtractionResponse")
                        .put("strict", true)
                        .put("schema", schema),
                ),
            )
            .put("temperature", 0)
    }

    private fun extractFieldsFromResponse(payload: JSONObject): Result<List<AzureExtractedField>> {
        val contentText = payload.optString("output_text").trim().ifBlank {
            val fromContent = payload
                .optJSONArray("output")
                ?.optJSONObject(0)
                ?.optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            fromContent.trim()
        }

        if (contentText.isBlank()) {
            return Result.failure(IllegalStateException("Empty response from Azure OpenAI."))
        }

        val cleaned = stripCodeFences(contentText)

        val extractedObject = try {
            JSONObject(cleaned)
        } catch (_: Throwable) {
            return Result.failure(IllegalStateException("Model did not return valid JSON: $contentText"))
        }

        val fields = listOf(
            "Amount",
            "MerchantName",
            "ExpenseCategory",
            "PaymentDateTime",
            "ItemDescription",
            "UpiTransactionId",
        )

        val extracted = fields.map { key ->
            AzureExtractedField(
                name = key,
                value = normalizeValue(extractedObject.opt(key)),
                confidence = null,
            )
        }

        return Result.success(extracted)
    }

    private fun stripCodeFences(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return trimmed
        return trimmed
            .removePrefix("```")
            .removePrefix("json")
            .removePrefix("JSON")
            .removeSuffix("```")
            .trim()
    }

    private fun resolveResponsesUrl(endpoint: String): String {
        val trimmed = endpoint.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/openai/v1", ignoreCase = true) -> "$trimmed/responses"
            trimmed.endsWith("/openai", ignoreCase = true) -> "$trimmed/v1/responses"
            else -> "$trimmed/openai/v1/responses"
        }
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
}

data class AzureExtractedField(
    val name: String,
    val value: String,
    val confidence: Double?,
)
