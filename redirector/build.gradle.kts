plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.huimao.redirector"
    compileSdk = 34

    defaultConfig {
        applicationId = (project.findProperty("proxyPackage") as String?) ?: "com.tencent.map"
        minSdk = 23
        targetSdk = 28
        versionCode = 9
        versionName = "1.8-huimao-map"
        buildConfigField("String", "PROXY_TYPE", "\"${(project.findProperty("proxyType") as String?) ?: "tencent"}\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { disable += "ExpiredTargetSdkVersion" }
}
