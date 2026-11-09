plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.willardzsw.texttack"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.willardzsw.texttack"
        // 安卓 11：无障碍截屏 takeScreenshot() 需要
        minSdk = 30
        targetSdk = 34
        // 功能改动后提高版本号
        versionCode = 3
        versionName = "0.2.1"

        ndk {
            // 只打 64 位 ARM，控制 ONNX Runtime 原生库体积
            abiFilters += listOf("arm64-v8a")
        }
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

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // 模型文件不压缩，便于直接读取
        noCompress += listOf("onnx")
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
