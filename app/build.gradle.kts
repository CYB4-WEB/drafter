plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.daftar.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.daftar.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        // Galaxy Tab S11 Ultra is arm64; x86_64 keeps the emulator working. Dropping 32-bit ABIs keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    androidResources {
        localeFilters += listOf("en", "ar")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    // Document scanner (camera → cropped pages); the UI/model ship with Google Play services, so the APK stays small.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
}
