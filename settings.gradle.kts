pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "touchpoint-android"

// The JVM tier: coordination logic with no Android dependency, the debug-only development backend,
// and the end-to-end harness (never published). Builds with nothing but a JDK, so the core is
// testable with no device and no Android SDK.
include(":touchpoint-core", ":touchpoint-development")
// The live end-to-end harness: internal, never published, and absent from the public tree.
if (file("touchpoint-e2e").isDirectory) include(":touchpoint-e2e")

// The Android tier needs an SDK. Without one the JVM tier still builds — useful as a
// quick unit tier — but `make check` passes -Ptouchpoint.requireAndroid=true so a missing
// SDK fails loudly instead of silently skipping half the products.
val androidSdkConfigured: Boolean = run {
    val local = file("local.properties")
    val fromLocal = local.isFile && local.readLines().any { Regex("^\\s*sdk\\.dir\\s*=\\s*\\S").containsMatchIn(it) }
    fromLocal || !System.getenv("ANDROID_HOME").isNullOrBlank() || !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()
}
val androidRequired = providers.gradleProperty("touchpoint.requireAndroid").orNull == "true"
if (androidSdkConfigured) {
    include(":touchpoint-core-android", ":touchpoint-ui", ":touchpoint-chat", ":touchpoint-voice", ":touchpoint-voice-telecom", ":touchpoint-livesync")
    // The Acme Bank demo: an app that integrates the SDK as a customer would. Never
    // published, and absent from the public tree until the demo's own release.
    if (file("demo/acme-bank").isDirectory) {
        include(":acme-bank")
        project(":acme-bank").projectDir = file("demo/acme-bank")
    }
} else if (androidRequired) {
    throw GradleException(
        "touchpoint.requireAndroid=true but no Android SDK is configured: set sdk.dir in local.properties or ANDROID_HOME",
    )
} else {
    logger.warn("touchpoint: no Android SDK configured — building the JVM tier only")
}
