// A bank app on older kotlinx-coroutines, the pin Android apps most often carry. Must resolve at Touchpoint's floor or above.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    // The SDK's own catalog, so the fixture's toolchain cannot drift from the SDK's.
    versionCatalogs { create("libs") { from(files("../../../../gradle/libs.versions.toml")) } }
}
rootProject.name = "below-coroutines"
// Touchpoint by source: the composite substitutes its modules for their coordinates, carrying the
// same declarations its published metadata would.
includeBuild("../../../..")
