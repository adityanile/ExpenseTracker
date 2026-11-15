package com.adityanile.expensetracker

data class AzureOpenAiSettings(
    val endpoint: String,
    val apiKey: String,
    val modelDeployment: String,
) {
    fun isConfigured(): Boolean {
        return endpoint.isNotBlank() && apiKey.isNotBlank() && modelDeployment.isNotBlank()
    }
}

object AzureOpenAiSettingsProvider {
    fun fromBuildConfig(): AzureOpenAiSettings {
        return AzureOpenAiSettings(
            endpoint = BuildConfig.AZURE_OPENAI_ENDPOINT,
            apiKey = BuildConfig.AZURE_OPENAI_API_KEY,
            modelDeployment = BuildConfig.AZURE_OPENAI_MODEL,
        )
    }
}
