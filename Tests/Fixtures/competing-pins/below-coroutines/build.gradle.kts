plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.bank"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
}

dependencies {
    implementation("software.aws.connect:touchpoint-chat")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

apply(from = "../host.gradle.kts")
