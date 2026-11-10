plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.finapp.daily"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.finapp.daily"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 注意：Compose BOM 2026.05 及以后（Compose 1.11+）要求 compileSdk 37 + AGP 9.1，
// 本项目锁定 compileSdk 36，所以选用 2026.03.01（Compose 1.10.6）。
// 若要升级 Compose，必须同时升级 AGP 与 compileSdk，别只改这一行。
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试跑在 JVM 上，需要一份真实的 org.json 实现
    // （Android 自带的 org.json 在 JVM 测试里只是抛异常的桩）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
