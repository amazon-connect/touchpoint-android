// Live Sync protocol client and the declarative action layer. Core only — a Live-Sync-only consumer
// links neither the chat SDK nor Chime. The declarations are Compose, as a screen declares what
// it can do while it is composed; Compose is AndroidX, which counts as platform, and a host that declares
// nothing has it stripped by R8.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.amazon.connect.touchpoint.livesync"
    compileSdk = 36
    resourcePrefix = "touchpoint_"

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // Schemas and payloads are JsonElement in the public API: kotlinx counts as platform.
    api(libs.kotlinx.serialization.json)
    // TouchpointAction, TouchpointScope and the debug overlay, sized by the UI kit's metrics.
    implementation(project(":touchpoint-ui"))
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.ui)
    // The socket and the context POST. Internal: no OkHttp type appears in the public API.
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real session, for the gate end to end: Core's store on the JVM.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    // The real OkHttp socket and POST against a local server: the production transport is tested too.
    testImplementation(libs.okhttp.mockwebserver)
    // The declarations through a real composition.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // A current Espresso: the Compose rule idles through it, and the default one fails on Android 17.
    androidTestImplementation(libs.androidx.test.espresso.core)
    // The empty activity the Compose test rule hosts content in; debug only, never in the AAR consumers get.
    debugImplementation(libs.compose.ui.test.manifest)
}
