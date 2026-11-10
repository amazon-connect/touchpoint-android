// Debug-only DevelopmentBackend. Kept out of release builds: a host
// declares this with `debugImplementation`, so a reference from release code is a compile error and
// the class cannot reach a release build. Scripts/check-release-symbols.sh asserts no published
// module carries it. Never declared as a dependency of any other published module.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    api(project(":touchpoint-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
