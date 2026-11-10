plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.backdropcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.backdropkit"
        minSdk = 26
        targetSdk = 35
        // Version follows the build number: build 52 is version 1.0.52 (release v1.0.52).
        val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "47").toInt()
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Phones only (keeps the app smaller than also shipping emulator x86 code)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    // Voice isolation (RNNoise, in C)
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    androidResources { noCompress += listOf("tflite") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // On-device person segmentation
    implementation("com.google.mediapipe:tasks-vision:0.10.21")

    // Google Drive upload (OAuth token via Identity Authorization API, upload via REST)
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR code for the second-phone remote link
    implementation("com.google.zxing:core:3.5.3")
}
