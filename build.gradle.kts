import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// Root build. Each module declares its own configuration explicitly; there are few enough that a
// convention plugin would hide more than it saves. The one shared rule is the Kotlin floor, below.
plugins {
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// The Kotlin floor. The compiler is 2.4, but what it emits must be readable by a
// host compiling with the floor version: metadata at the floor's language and API version, and the
// floor's stdlib as the dependency every module declares. One place, every module.
val kotlinFloor: KotlinVersion = KotlinVersion.fromVersion(libs.versions.kotlinFloor.get())
val kotlinStdlibFloor: String = libs.versions.kotlinStdlibFloor.get()
subprojects {
    for (id in listOf("org.jetbrains.kotlin.jvm", "com.android.library")) {
        pluginManager.withPlugin(id) {
            extensions.configure<KotlinProjectExtension> { coreLibrariesVersion = kotlinStdlibFloor }
        }
    }
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            languageVersion.set(kotlinFloor)
            apiVersion.set(kotlinFloor)
        }
    }
}

// Security floors for the build's own tooling and the device tests: Android lint's classpath (AGP
// 9.5.0 brings Bouncy Castle 1.80.2, Guava 33.4.0-jre, Commons Lang 3.16.0, HttpClient 4.5.6) and
// the instrumented-test classpaths (the accessibility test framework brings protobuf-javalite
// 3.19.1 and jsoup 1.15.1; Chime's test path Guava 31.0.1). None of it reaches a published artifact
// or a host, so it is raised here rather than in gradle/libs.versions.toml: each version is the
// first that fixes the known vulnerability, and only a lower request is raised. The library's own
// floors (gson, guava on the release classpath) are touchpoint-chat's constraints.
val toolingFloors =
    mapOf(
        "org.bouncycastle:bcprov-jdk18on" to "1.85", // CVE-2026-8763, CVE-2026-13506
        "org.bouncycastle:bcpkix-jdk18on" to "1.85", // CVE-2026-5588; kept with bcprov
        "org.bouncycastle:bcutil-jdk18on" to "1.85", // kept with bcprov
        "org.apache.commons:commons-lang3" to "3.18.0", // CVE-2025-48924
        "org.apache.httpcomponents:httpclient" to "4.5.13", // CVE-2020-13956
        "com.google.protobuf:protobuf-javalite" to "3.25.5", // CVE-2022-3510, CVE-2024-7254, CVE-2022-3171
        "org.jsoup:jsoup" to "1.23.2", // CVE-2026-75140, CVE-2022-36033, CVE-2026-71497
    )

// Guava is published as two flavours; each keeps its own.
fun guavaFloor(requested: String): String = if (requested.endsWith("-jre")) "33.7.2-jre" else "33.7.2-android" // CVE-2026-102554, CVE-2023-2976

fun versionLess(a: String, b: String): Boolean {
    val x = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d < 0
    }
    return false
}

fun isToolingOrDeviceTest(configuration: String): Boolean =
    configuration == "androidLintTool" || configuration.contains("AndroidTest")

subprojects {
    configurations.configureEach {
        if (!isToolingOrDeviceTest(name)) return@configureEach
        resolutionStrategy.eachDependency {
            val module = "${requested.group}:${requested.name}"
            val version = requested.version ?: return@eachDependency
            val floor = if (module == "com.google.guava:guava") guavaFloor(version) else toolingFloors[module] ?: return@eachDependency
            if (versionLess(version, floor)) {
                useVersion(floor)
                because("security floor for the build's tooling and device tests (not published)")
            }
        }
    }
}

// Publishing to Maven Central: the SDK's seven libraries, each with its sources, its javadoc and a
// full POM. Gradle only writes them, unsigned, under build/staging-deploy
// (`./gradlew publishAllPublicationsToLocalStagingRepository`); Scripts/release-bundle.sh zips that
// for the Central Portal, and Scripts/sign-and-upload.sh signs and uploads it where the team's key
// is kept. No credential is ever read here.
val published =
    mapOf(
        "touchpoint-core" to "Amazon Connect Customer Touchpoint for Android: configuration, credential brokering, sessions, the durable store and events. Pure Kotlin.",
        "touchpoint-core-android" to "Amazon Connect Customer Touchpoint for Android: the Keystore-backed secure store, SQLite and shared resources.",
        "touchpoint-ui" to "Amazon Connect Customer Touchpoint for Android: the theme, sizes, icons, words and controls chat and voice share.",
        "touchpoint-chat" to "Amazon Connect Customer Touchpoint for Android: chat with an Amazon Connect contact, its transcript UI, and new-message reporting for the host.",
        "touchpoint-voice" to "Amazon Connect Customer Touchpoint for Android: in-app calls over WebRTC, and screen sharing.",
        "touchpoint-voice-telecom" to "Amazon Connect Customer Touchpoint for Android: in-app calls that go on in the background, through Jetpack core-telecom.",
        "touchpoint-livesync" to "Amazon Connect Customer Touchpoint for Android: Live Sync, its consent, and Compose declarations of what each screen can do.",
    )
// Never published: touchpoint-development, a development backend.
subprojects {
    val about = published[name] ?: return@subprojects
    apply(plugin = "maven-publish")
    // The public API, recorded and checked: Kotlin's own ABI validation, so a change to what hosts
    // compile against is a reviewed diff of api/*.api, never silent (`./gradlew updateKotlinAbi`).
    // The JVM modules only, for now: Kotlin 2.4's validation does not yet read AGP 9's Android
    // binaries (its dumps come out empty).
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
            abiValidation { }
        }
    }
    // Each module's component, once its plugin has made it: AGP makes `release` in its own
    // afterEvaluate, so ours is registered after it, from inside its plugin's callback.
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                    withJavadocJar()
                }
            }
        }
        afterEvaluate { publish(about, "release") }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        afterEvaluate { publish(about, "java") }
    }
}

fun Project.publish(
    about: String,
    component: String,
) {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components.getByName(component))
                artifactId = project.name
                pom {
                    name.set(project.name)
                    description.set(about)
                    url.set(providers.gradleProperty("POM_URL"))
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/license/mit")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("amazonwebservices")
                            name.set("Amazon Web Services")
                            organization.set("Amazon Web Services")
                            organizationUrl.set("https://aws.amazon.com")
                        }
                    }
                    scm {
                        url.set(providers.gradleProperty("POM_URL"))
                        connection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
                        developerConnection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
                    }
                }
            }
        }
        repositories {
            maven {
                name = "localStaging"
                url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }
}
