plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.huimao.map"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.huimao.map"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "1.0.22"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.datastore.preferences)
    // 百度普通 Android 导航 SDK（本地 AAR 开发包，已包含 地图/定位/搜索/导航 全部模块）
    // BaiduLBS_Android.aar = 地图+定位+搜索+工具 all-in-one
    // onsdk_all.aar = 导航引擎
    // NaviTts.aar = 导航 TTS
    implementation(files("libs/BaiduLBS_Android.aar"))
    implementation(files("libs/onsdk_all.aar"))
    implementation(files("libs/NaviTts.aar"))
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)
    // 百度定位 SDK 9.6.4 内部依赖 OkHttp3（pom 未声明，需手动添加）
    implementation(libs.okhttp3)
    implementation(libs.okhttp3.logging)
    // 百度导航原生 UI 布局直接引用，离线 AAR 不会携带 Maven 传递依赖。
    implementation("com.airbnb.android:lottie:6.4.0")
    implementation("com.facebook.fresco:fresco:3.2.0")
    // 百度导航 SDK 的沿途推荐、全景/自定义图标面板运行时引用 Glide。
    implementation("com.github.bumptech.glide:glide:4.16.0")
    debugImplementation(libs.androidx.ui.tooling)
}
