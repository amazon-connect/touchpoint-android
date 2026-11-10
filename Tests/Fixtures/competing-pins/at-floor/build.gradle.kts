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
    implementation("software.aws.connect:amazon-connect-chat-android") { version { strictly("3.0.0") } }
    implementation("software.aws.chimesdk:amazon-chime-sdk") { version { strictly("0.25.5") } }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core") { version { strictly("1.11.0") } }
    implementation("software.aws.connect:touchpoint-voice")
}

apply(from = "../host.gradle.kts")
