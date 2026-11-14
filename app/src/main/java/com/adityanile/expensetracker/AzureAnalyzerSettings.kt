package com.adityanile.expensetracker

data class AzureAnalyzerSettings(
    val endpoint: String,
    val apiKey: String,
    val analyzerId: String,
    val apiVersion: String,
) {
    fun isConfigured(): Boolean {
        return endpoint.isNotBlank() && apiKey.isNotBlank() && analyzerId.isNotBlank() && apiVersion.isNotBlank()
    }
}

object AzureAnalyzerSettingsProvider {
    fun fromBuildConfig(): AzureAnalyzerSettings {
        return AzureAnalyzerSettings(
            endpoint = BuildConfig.AZURE_CONTENT_UNDERSTANDING_ENDPOINT,
            apiKey = BuildConfig.AZURE_CONTENT_UNDERSTANDING_KEY,
            analyzerId = BuildConfig.AZURE_CONTENT_UNDERSTANDING_ANALYZER_ID,
            apiVersion = BuildConfig.AZURE_CONTENT_UNDERSTANDING_API_VERSION,
        )
    }
}
