// A bank app that already depends on amazon-connect-chat-android at a version below Touchpoint's floor. Must resolve at the floor or above: Gradle takes the highest version asked for.
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
rootProject.name = "below-chat"
// Touchpoint by source: the composite substitutes its modules for their coordinates, carrying the
// same declarations its published metadata would.
includeBuild("../../../..")
