// 应用模块构建脚本。
// 契约：docs/CONTRACT.md 第 5 节。依赖集合是最小集，不要擅自新增。
// 注意（实测）：AGP 9.4.1 自带 Kotlin 支持，**不能**再应用 org.jetbrains.kotlin.android，
// 否则 fatal：'The org.jetbrains.kotlin.android plugin is no longer required for Kotlin
// support since AGP 9.0'。Compose 编译器插件仍然要单独应用。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lazyrotate.app"
    // compileSdk 必须是 37：契约里要求的 androidx 最新稳定版（core-ktx 1.19.1、
    // Compose BOM 2026.09.00 → ui-android 1.12.1）的 AAR metadata 要求 compileSdk >= 37，
    // 否则 checkDebugAarMetadata 直接失败。`compileSdk = 37` 由 AGP 解析到
    // `.android-sdk\platforms\android-37.0`（AGP 认这个带点的目录名），实测可用。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lazyrotate.app"
        minSdk = 24
        // targetSdk 保持 36：拿到 Android 16 的大屏方向行为变更，同时不提前吃 Android 17 的行为变更。
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 契约第 5 节：release 也不混淆。
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        // 实测：AGP 9 内置 Kotlin 直接把 jvmTarget 对齐到 compileOptions（产物 class major=61/Java17），
        // 不需要再写 kotlin { compilerOptions { jvmTarget } }。
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
