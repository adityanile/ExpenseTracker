package com.adityanile.expensetracker

data class AzureTableSettings(
    val endpoint: String,
    val tableName: String,
    val sasToken: String,
) {
    fun isConfigured(): Boolean {
        return endpoint.isNotBlank() && tableName.isNotBlank() && sasToken.isNotBlank()
    }
}

object AzureTableSettingsProvider {
    fun fromBuildConfig(): AzureTableSettings {
        return AzureTableSettings(
            endpoint = BuildConfig.AZURE_TABLE_ENDPOINT,
            tableName = BuildConfig.AZURE_TABLE_NAME,
            sasToken = BuildConfig.AZURE_TABLE_SAS_TOKEN,
        )
    }
}
