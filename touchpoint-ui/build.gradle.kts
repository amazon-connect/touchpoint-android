// Touchpoint's UI kit, shared by the chat and voice surfaces: web's theme, sizes, icons, options,
// copy and common controls. Links neither the Connect chat SDK nor Chime — a voice-only host
// gets web's look without the chat SDK, and a chat-only one without WebRTC.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.amazon.connect.touchpoint.ui"
    compileSdk = 36
    resourcePrefix = "touchpoint_"

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The chat SDK 3.0 (the AWS SDK for Kotlin under it) requires core library desugaring of every
        // app that links it: this module's test APK, and so a host's app too (README).
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":touchpoint-core-android"))
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.ui)
}
