// Injected by the gate scripts with `-I`. Registers one read-only reporting task per Android module and
// changes nothing else in the build: it prints the external AARs on the module's release runtime
// classpath, which is exactly the set of library manifests a host's manifest merger will see. The
// classpath is resolved when the task runs, because AGP creates variant configurations late.
allprojects {
    pluginManager.withPlugin("com.android.library") {
        tasks.register("touchpointExternalAars") {
            doLast {
                val classpath = project.configurations.getByName("releaseRuntimeClasspath")
                classpath.incoming.artifactView {
                    lenient(true)
                    attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
                    componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                }.artifacts.forEach {
                    println("TOUCHPOINT-AAR\t${project.name}\t${it.id.componentIdentifier.displayName}\t${it.file}")
                }
            }
        }
    }
}
