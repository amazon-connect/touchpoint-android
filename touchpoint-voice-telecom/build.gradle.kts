// Opt-in: a call that survives the background, through Jetpack core-telecom (plan item 15, decided
// 2026-10-20). A separate module because core-telecom merges a ConnectionService, a receiver and three
// permissions into the host's manifest, and this module declares a foreground service and its
// permissions: a host that adds it has chosen them, and touchpoint-voice still declares nothing.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.amazon.connect.touchpoint.voice.telecom"
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
    api(project(":touchpoint-voice"))
    // AndroidX (platform). Internal: no Telecom type appears in the public API.
    implementation(libs.androidx.core.telecom)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
