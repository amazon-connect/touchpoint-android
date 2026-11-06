// A bank app on an older AWS SDK — not our dependency but the Connect chat SDK's. Must resolve at what that SDK needs or above.
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
rootProject.name = "below-aws"
// Touchpoint by source: the composite substitutes its modules for their coordinates, carrying the
// same declarations its published metadata would.
includeBuild("../../../..")
