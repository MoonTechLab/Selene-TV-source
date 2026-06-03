plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "org.moontechlab.selenetv.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // macrobenchmark / baseline profile 生成要求 minSdk >= 28（仅生成模块，不影响 app 的 minSdk 23）
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // 被测 app
    targetProjectPath = ":app"
}

// 用已连接设备/模拟器生成（无 Gradle Managed Device）。在 arm64 模拟器上跑即可，
// 生成的 profile 是类/方法清单，跨设备通用，随 release 打包后由 ART 在 armv7a 真机上 AOT。
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
