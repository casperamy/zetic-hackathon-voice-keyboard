import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

android {
    namespace = "com.aaryaharkare.voicekeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aaryaharkare.voicekeyboard"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PERSONAL_KEY", "\"${localProperties.getProperty("PERSONAL_KEY") ?: ""}\"")
        buildConfigField("String", "WHISPER_ENCODER_MODEL", "\"${localProperties.getProperty("WHISPER_ENCODER_MODEL") ?: ""}\"")
        buildConfigField("String", "WHISPER_DECODER_MODEL", "\"${localProperties.getProperty("WHISPER_DECODER_MODEL") ?: ""}\"")
        buildConfigField("String", "FORMATTER_LLM_MODEL", "\"Qwen/Qwen3-0.6B\"")
        buildConfigField(
            "int",
            "FORMATTER_LLM_VERSION",
            "1"
        )
        buildConfigField("String", "FORMATTER_LLM_MODE", "\"RUN_SPEED\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("com.zeticai.mlange:mlange:1.5.7")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
