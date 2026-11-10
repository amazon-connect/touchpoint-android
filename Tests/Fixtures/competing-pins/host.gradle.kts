// Shared by every competing-pins fixture: resolve the host's release runtime graph — the graph only,
// so nothing is compiled — print each unresolved module with Gradle's full explanation, and the
// versions the watched modules settled on. Fails when anything is unresolved.
val watched = listOf(
    "software.aws.connect:amazon-connect-chat-android",
    "aws.sdk.kotlin:connectparticipant",
    "software.aws.chimesdk:amazon-chime-sdk",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
)

tasks.register("resolvePins") {
    val classpath = configurations.getByName("releaseRuntimeClasspath")
    doLast {
        val result = classpath.incoming.resolutionResult
        val failures = result.allDependencies.filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
        for (f in failures) {
            var cause: Throwable? = f.failure
            while (cause != null) {
                println("UNRESOLVED ${f.requested.displayName}: ${cause.message}")
                cause = cause.cause
            }
        }
        for (c in result.allComponents) {
            val id = c.moduleVersion ?: continue
            if ("${id.group}:${id.name}" in watched) println("RESOLVED ${id.group}:${id.name}:${id.version}")
        }
        if (failures.isNotEmpty()) throw GradleException("competing pins: ${failures.size} unresolved")
    }
}
