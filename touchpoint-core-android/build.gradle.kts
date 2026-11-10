// The Android half of Core: the platform adapters every product needs (the Keystore-backed secure
// store, the framework SQLite driver, the no-backup store location) and the shared string
// resources. Hosts never declare it; each product brings it in transitively. Links neither
// the Connect chat SDK nor Chime.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.amazon.connect.touchpoint.core.android"
    compileSdk = 36
    // Library resources merge into the host app's namespace, and a host resource with the same name
    // silently wins. The prefix keeps ours from colliding; check-string-resources.sh enforces it.
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
    api(project(":touchpoint-core"))
    implementation(libs.androidx.sqlite.framework)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
