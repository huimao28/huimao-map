plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.huimao.map.wear"
    // 当前公开工具链可用的最高稳定 SDK；兼容后续系统运行。
    compileSdk = 36

    defaultConfig {
        // 与手机端共用包名；Wear OS 通过 uses-feature 作为同一应用的设备版本分发。
        applicationId = "com.huimao.map"
        minSdk = 26
        targetSdk = 36
        // Wear OS 使用独立的高位版本序列，避免与手机端版本号混淆。
        versionCode = 200002
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("HUIMAO_KEYSTORE_PATH") ?: "../keystore/release.jks")
            storePassword = System.getenv("HUIMAO_KEYSTORE_PASSWORD")
                ?: providers.gradleProperty("HUIMAO_KEYSTORE_PASSWORD").orNull
                ?: error("Missing HUIMAO_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("HUIMAO_KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("HUIMAO_KEY_PASSWORD")
                ?: providers.gradleProperty("HUIMAO_KEY_PASSWORD").orNull
                ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    // Compose Compiler 由 Kotlin Compose Gradle Plugin 管理
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.wear.ongoing)
}
