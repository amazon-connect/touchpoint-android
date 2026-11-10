plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.bank"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
}

dependencies {
    implementation("software.aws.connect:touchpoint-voice")
    implementation("software.aws.chimesdk:amazon-chime-sdk:0.25.0")
}

apply(from = "../host.gradle.kts")
