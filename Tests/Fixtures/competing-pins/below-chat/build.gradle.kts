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
    implementation("software.aws.connect:amazon-connect-chat-android:2.0.0")
}

apply(from = "../host.gradle.kts")
