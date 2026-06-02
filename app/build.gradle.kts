import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.1.0"
}

// 稳定签名：从仓库根目录 key.properties 读取密码/别名，storeFile 默认指向已提交的 key.jks。
// CI 在构建前用 secrets 写出 key.properties；本地缺失时回退 debug 签名，保证可正常编译。
val keystorePropsFile = rootProject.file("key.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val keystoreFile = rootProject.file(keystoreProps.getProperty("storeFile") ?: "key.jks")
val hasReleaseSigning = keystorePropsFile.exists() && keystoreFile.exists()

android {
    namespace = "org.moontechlab.selenetv"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.moontechlab.selenetv"
        minSdk = 23
        targetSdk = 36
        versionCode = 13
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = keystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 有 key.properties 时用稳定 release 签名；否则回退 debug 以便本地构建
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
    splits {
        abi {
            // 开启分包功能
            isEnable = true

            // 重置默认列表（默认包含所有架构）
            reset()

            // 明确指定要分离的架构
            // 注意：armv8 对应 "arm64-v8a"，armv7a 对应 "armeabi-v7a"
            include("arm64-v8a", "armeabi-v7a")

            // 是否还需要生成一个包含所有架构的通用包 (Fat APK)？
            // 如果你只想要两个独立的包，设为 false。
            // 如果你想顺便打一个“通吃”的包，设为 true。
            isUniversalApk = false
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("androidx.tv:tv-material:1.1.0-alpha01")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.compose.animation.core)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.libmpv)

    // 弹幕渲染引擎（开源，OpenGL，纯 Kotlin 无 .so）
    implementation("com.kuaishou:akdanmaku:1.0.3")

    // Ktor Server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-html-builder:2.3.12")
    
    // QRCode
    implementation("io.github.g0dkar:qrcode-kotlin:4.2.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}