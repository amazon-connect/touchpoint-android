import java.util.Properties

// Acme Bank: a fictional bank app that integrates the Touchpoint SDK the way a customer would, by
// module dependency and through the public API only. Everything in it is synthetic.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// A brand folder: `partner/`, compiled in when asked for with -PpartnerBrand=true, renames and
// recolours the bank. Acme's (`brand-acme/`) otherwise, so a plain build is
// always Acme Bank, whatever folders the checkout has.
val partner = providers.gradleProperty("partnerBrand").orNull == "true"
if (partner && !file("partner").isDirectory) throw GradleException("-PpartnerBrand=true, but demo/acme-bank/partner/ does not exist")
val appLabel =
    if (partner) {
        Properties().apply { file("partner/brand.properties").reader().use { load(it) } }.getProperty("appLabel")
    } else {
        "Acme Bank"
    }

// Live mode's backend (debug builds only): the demo backend's endpoint, its Region, the Cognito ids
// demo users sign in with, and the Connect instance's URL. None is a secret; the file is gitignored
// because it names one deployment. Without it, live mode is set up over adb or in Settings.
val live =
    Properties().apply {
        val file = file("live.properties")
        if (file.isFile) file.reader().use { load(it) }
    }
fun liveValue(key: String): String = live.getProperty(key, "").trim().replace("\\", "").replace("\"", "")

android {
    namespace = "com.acmebank.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.acmebank.demo.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = appLabel
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            for ((field, key) in listOf(
                "LIVE_ENDPOINT" to "endpoint",
                "LIVE_REGION" to "region",
                "LIVE_USER_POOL_ID" to "userPoolId",
                "LIVE_CLIENT_ID" to "clientId",
                "LIVE_IDENTITY_POOL_ID" to "identityPoolId",
                "LIVE_CONNECT_INSTANCE_URL" to "connectInstanceUrl",
            )) {
                buildConfigField("String", field, "\"${liveValue(key)}\"")
            }
        }
    }

    sourceSets["main"].kotlin.directories += if (partner) "partner/kotlin" else "brand-acme/kotlin"
    if (partner) sourceSets["main"].res.directories += "partner/res"

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
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":touchpoint-chat"))
    implementation(project(":touchpoint-core-android"))
    implementation(project(":touchpoint-livesync"))
    implementation(project(":touchpoint-voice"))
    // Opted in: an in-app call that goes on in the background.
    implementation(project(":touchpoint-voice-telecom"))
    implementation(libs.androidx.lifecycle.process)
    // The development backend vends Live Sync literals: debug only, never in a release APK.
    debugImplementation(project(":touchpoint-development"))
    implementation(platform(libs.compose.bom))
    // The bank's own screens are Material: a host app's, not the SDK's (which links no Material).
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real TouchpointSession in the Live Sync gate test needs its store's SQLite on the JVM.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
