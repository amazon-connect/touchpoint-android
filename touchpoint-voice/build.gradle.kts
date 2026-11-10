// WebRTC session adapter, outbound Telecom integration, call UI. The only module that links Chime, and
// it links Chime's full media distribution, the SDK's default and what Connect's own Android in-app
// calling sample ships: screen sharing sends video, and the no-video-codecs build ("audio only") offers
// no codec to send it with (seen on device, 2026-10-21).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.amazon.connect.touchpoint.voice"
    compileSdk = 36
    resourcePrefix = "touchpoint_"

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The chat SDK 3.0 (the AWS SDK for Kotlin under it) requires core library desugaring of every
        // app that links it: this module's test APK, and so a host's app too (README).
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":touchpoint-core-android"))
    api(project(":touchpoint-ui"))
    implementation(libs.chime.sdk)
    implementation(libs.chime.media)
    // The microphone asked for when a call starts, and Back out of the settings. AndroidX (platform).
    implementation(libs.androidx.activity.compose)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The real session and its store, for the voice Live Sync gate.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.sqlite.framework)
    // The empty activity the Compose test rule hosts content in; debug only, never in the AAR consumers get.
    debugImplementation(libs.compose.ui.test.manifest)
}
