import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

// Release signing is read from a gitignored `keystore.properties` (never committed). When it's
// absent (fresh clone / CI without the keystore), the release build falls back to the debug
// keystore so it still assembles — just not with the real release identity.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKeystore) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.clearcall"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.shivarya.clearcall"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Google Sign-In web client ID — not secret (it's the ID token audience), used as
        // the Credential Manager serverClientId and verified server-side as GOOGLE_CLIENT_ID.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"660724285686-76jujsevciv9hqc9ea6g1gd0lu8detlj.apps.googleusercontent.com\"",
        )

        // 64-bit only (Play requires 64-bit; x86_64 keeps the emulator working). The ML payload
        // (sherpa-onnx + DFN + WebRTC ship 4 ABIs each) roughly doubles otherwise.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Reach the local `php -S localhost:8010` dev server via `adb reverse
            // tcp:8010 tcp:8010` on BOTH the emulator and a physical device — more
            // reliable than the 10.0.2.2 host alias (same convention as diet-plan).
            buildConfigField("String", "API_BASE_URL", "\"http://localhost:8010\"")
        }
        release {
            isMinifyEnabled = false
            // Real release keystore when keystore.properties is present; otherwise fall back to
            // the debug keystore so a fresh clone can still assemble a (non-distributable) release.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField("String", "API_BASE_URL", "\"https://shivarya.dev/clear_call\"")
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
        compose = true
        buildConfig = true
    }
    androidResources {
        // Model files are memory-mapped/streamed by native code at load; don't compress them.
        noCompress += "onnx"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.15.0")

    // Calling transport
    implementation("io.livekit:livekit-android:2.26.1")

    // Push (ring wake) + Google Sign-In
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Networking + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Contact-code QR generation
    implementation("com.google.zxing:core:3.5.3")

    // QR scan (camera) + gallery decode: CameraX + ML Kit barcode scanning.
    // CameraX 1.4.2 ships 16 KB-page-aligned native libs (1.3.4's libimage_processing_util_jni.so
    // was 4 KB-aligned and failed Android 15+'s 16 KB requirement); needs AGP 8.9+.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Biometric app lock
    implementation("androidx.biometric:biometric:1.1.0")

    // On-device noise suppression (DeepFilterNet3), P2
    implementation("io.github.kaleyravideo:android-deepfilternet:0.0.8")

    // Speaker embedding (WeSpeaker CAM++) + Silero VAD for "Isolate a voice", P4.
    // Official prebuilt AAR (Apache-2.0), gitignored — fetch with scripts/fetch-ml-assets.ps1
    // along with the .onnx model assets. Statically links its own onnxruntime.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar"))
}
