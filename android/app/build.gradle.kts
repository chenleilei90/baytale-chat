import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 读取本地签名配置（绝不提交真实密钥库与密码至开源仓库）
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.baytale.baychat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.baytale.baychat"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "1.8.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://chat.baytale.com\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        getByName("debug") {
            // 调试包同样开启 V1+V2+V3，避免国产机型或浏览器安装器提示缺少签名文件
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 同时启用 V1 (META-INF/*.RSA) + V2 + V3 签名，兼容全版本 Android、国产定制系统与 Google Play
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 网络通信：OkHttp (HTTP + WebSocket) 与 Kotlinx Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 原生 WebRTC 引擎（Stream 维护的标准 Google libwebrtc Android AAR，兼容 Android 15）
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    // 原生高性能图片加载
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 无 GMS 依赖的原生扫码 + 离线二维码生成（同时兼容谷歌商店与小米等国内机型）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
