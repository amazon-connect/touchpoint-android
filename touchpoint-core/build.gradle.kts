// Coordination, persistence logic, credential brokering, event model. Pure Kotlin/JVM: no android.*
// import can compile here, which is what keeps the coordinator testable with no device and
// drivable from a harness on a development machine. Platform adapters live in
// touchpoint-core-android. Links neither the Connect chat SDK nor Chime.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    // Flow, StateFlow and SQLiteDriver appear in public signatures, so these are api, not implementation.
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.sqlite)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real SQLite on the JVM, so the store is tested against the engine it runs on, not a fake.
    testImplementation(libs.androidx.sqlite.bundled)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Lets a test assert Touchpoint.VERSION matches the build's version, so the two cannot drift.
    systemProperty("touchpoint.version", project.version.toString())
}
