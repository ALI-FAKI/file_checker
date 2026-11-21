plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ali.receiptshield"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ali.receiptshield"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // REQUIRED: Provides lifecycleScope for safe Coroutine execution in MainActivity
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // ZXing for Offline QR Code Generation & Parsing
    implementation("com.google.zxing:core:3.5.3")

    // Kotlin Coroutines for non-blocking UI background processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
