plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
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
            // No dedicated release keystore yet (personal sideload only, not Play Store) —
            // reuse the auto-generated debug keystore so assembleRelease is installable.
            signingConfig = signingConfigs.getByName("debug")
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
    // CameraX pinned to 1.3.x — 1.4+/1.6+ require AGP 8.9+, we're on 8.7.3.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Biometric app lock
    implementation("androidx.biometric:biometric:1.1.0")
}
