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
        versionCode = 3
        versionName = "2.0"
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // JavaCPP native jars also carry headers, pkg-config/cmake files and a CLI binary next to the .so files.
            excludes += listOf("lib/*/include/**", "lib/*/lib/**", "lib/*/bin/**", "lib/*/share/**", "lib/*/tesseract", "META-INF/native-image/**")
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    // Installs the Compose/AndroidX baseline profiles for sideloaded APKs too → much less jank on first runs.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
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
    // ml-agent: on-device OCR + translation (see docs/logs/ml-agent.md for sizes and the choices).
    // Latin OCR: ML Kit Text Recognition v2, Play-services "thin" variant (~80 KB; the model comes with Play services).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Language ID: thin variant too (~240 KB instead of ~2.3 MB bundled).
    implementation("com.google.android.gms:play-services-mlkit-language-id:17.0.0")
    // Translation: no thin variant exists; native lib ≈ 16 MB per ABI, language models download on demand (~30 MB each).
    implementation("com.google.mlkit:translate:17.0.3")
    // Arabic OCR: Tesseract 5.5 (LSTM) via JavaCPP presets, from Maven Central (no JitPack). Natives per ABI;
    // ara.traineddata is downloaded on first use (not in the APK).
    implementation("org.bytedeco:tesseract:5.5.0-1.5.11")
    implementation("org.bytedeco:tesseract:5.5.0-1.5.11:android-arm64")
    implementation("org.bytedeco:leptonica:1.85.0-1.5.11:android-arm64")
    implementation("org.bytedeco:javacpp:1.5.11:android-arm64")
    // x86_64 natives only for debug builds (emulator); release x86_64 falls back to ML Kit Latin only.
    debugImplementation("org.bytedeco:tesseract:5.5.0-1.5.11:android-x86_64")
    debugImplementation("org.bytedeco:leptonica:1.85.0-1.5.11:android-x86_64")
    debugImplementation("org.bytedeco:javacpp:1.5.11:android-x86_64")
}
