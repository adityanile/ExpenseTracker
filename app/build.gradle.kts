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

    val azureOpenAiEndpoint = resolveSecret(
        "AZURE_OPENAI_ENDPOINT",
        resolveSecret("AZURE_CONTENT_UNDERSTANDING_ENDPOINT"),
    )
    val azureOpenAiApiKey = resolveSecret(
        "AZURE_OPENAI_API_KEY",
        resolveSecret("AZURE_CONTENT_UNDERSTANDING_KEY"),
    )
    val azureOpenAiModel = resolveSecret(
        "AZURE_OPENAI_MODEL",
        resolveSecret("AZURE_CONTENT_UNDERSTANDING_ANALYZER_ID", "gpt-4.1-mini"),
    )

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

        buildConfigField("String", "AZURE_OPENAI_ENDPOINT", azureOpenAiEndpoint.asBuildConfigValue())
        buildConfigField("String", "AZURE_OPENAI_API_KEY", azureOpenAiApiKey.asBuildConfigValue())
        buildConfigField("String", "AZURE_OPENAI_MODEL", azureOpenAiModel.asBuildConfigValue())

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