import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.adityanile.expensetracker"
    compileSdk {
        version = release(37)
    }

    val externalSecrets = Properties().apply {
        val candidates = listOf(
            rootProject.file(".gradle/gradle.properties"),
            rootProject.file("local.properties"),
        )
        candidates.forEach { file ->
            if (file.exists()) {
                file.inputStream().use { load(it) }
            }
        }
    }

    fun resolveSecret(name: String, fallback: String = ""): String {
        val gradleValue = providers.gradleProperty(name).orNull
        val envValue = providers.environmentVariable(name).orNull
        val externalValue = externalSecrets.getProperty(name)

        return when {
            !gradleValue.isNullOrBlank() -> gradleValue
            !envValue.isNullOrBlank() -> envValue
            !externalValue.isNullOrBlank() -> externalValue
            else -> fallback
        }
    }

    val azureEndpoint = resolveSecret("AZURE_CONTENT_UNDERSTANDING_ENDPOINT")
    val azureKey = resolveSecret("AZURE_CONTENT_UNDERSTANDING_KEY")
    val azureAnalyzerId = resolveSecret("AZURE_CONTENT_UNDERSTANDING_ANALYZER_ID", "expenseanalyser")
    val azureApiVersion = resolveSecret("AZURE_CONTENT_UNDERSTANDING_API_VERSION", "2025-11-01")

    val azureTableEndpoint = resolveSecret("AZURE_TABLE_ENDPOINT")
    val azureTableName = resolveSecret("AZURE_TABLE_NAME")
    val azureTableSasToken = resolveSecret("AZURE_TABLE_SAS_TOKEN")

    fun String.asBuildConfigValue(): String {
        val escaped = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }

    defaultConfig {
        applicationId = "com.adityanile.expensetracker"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AZURE_CONTENT_UNDERSTANDING_ENDPOINT", azureEndpoint.asBuildConfigValue())
        buildConfigField("String", "AZURE_CONTENT_UNDERSTANDING_KEY", azureKey.asBuildConfigValue())
        buildConfigField("String", "AZURE_CONTENT_UNDERSTANDING_ANALYZER_ID", azureAnalyzerId.asBuildConfigValue())
        buildConfigField("String", "AZURE_CONTENT_UNDERSTANDING_API_VERSION", azureApiVersion.asBuildConfigValue())

        buildConfigField("String", "AZURE_TABLE_ENDPOINT", azureTableEndpoint.asBuildConfigValue())
        buildConfigField("String", "AZURE_TABLE_NAME", azureTableName.asBuildConfigValue())
        buildConfigField("String", "AZURE_TABLE_SAS_TOKEN", azureTableSasToken.asBuildConfigValue())
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}