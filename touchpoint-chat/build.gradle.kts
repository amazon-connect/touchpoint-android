// Chat transport and transcript UI. Wraps amazon-connect-chat-android; must never link
// Chime — Scripts/check-build-graph.sh walks this module's resolved release classpath.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.amazon.connect.touchpoint.chat"
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
    // Web's look: theme, sizes, icons, copy and common controls, shared with voice.
    api(project(":touchpoint-ui"))
    implementation(libs.connect.chat)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    // BackHandler: system Back leaves the settings before it closes the sheet. AndroidX (platform).
    implementation(libs.androidx.activity.compose)
    // Custom Tabs: a message's link opens over the host, as web's opens in a new tab. AndroidX (platform).
    implementation(libs.androidx.browser)
    // A video message plays in its card (HLS or a file), with Media3's own controls. AndroidX (platform).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    // Sign-in's listen-only socket and the participant calls the chat SDK does not make. Already in
    // the graph through the chat SDK; internal: no OkHttp type appears in the public API.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // Security floors for libraries we do not use directly: a constraint adds nothing to the graph,
    // it only raises a version a dependency already brings. Published, so a host resolves them too.
    constraints {
        implementation(libs.gson) { because("CVE-2022-25647: the chat SDK 3.0 resolves gson 2.8.5, through Retrofit") }
        implementation(libs.guava) { because("CVE-2026-102554: Media3 1.11.1 resolves guava 33.3.1-android") }
    }

    // The session over this transport, against real SQLite on the JVM.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)

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
