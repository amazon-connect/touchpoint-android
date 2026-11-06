// A bank app that pins amazon-connect-chat-android STRICTLY below Touchpoint's floor. Gradle lets the consuming build win over anything a library publishes; what the check holds is that the integrator's own report names the module, our floor and their pin.
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
rootProject.name = "strict-below-chat"
// Touchpoint by source: the composite substitutes its modules for their coordinates, carrying the
// same declarations its published metadata would.
includeBuild("../../../..")
