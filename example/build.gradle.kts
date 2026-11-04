import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.environment.Theme
import kotlin.io.path.Path

plugins {
    alias(libs.plugins.mps.env.gen)
}

repositories {
    // itemis nexus
    maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
    mavenLocal()
}

// MPS extensions, used by mps-project as the project library "dependencies" (build/dependencies)
val myDependencyPath = Path(project.layout.buildDirectory.get().toString(), "dependencies")

val mpsExtensions: Configuration = configurations.create("mpsExtensions")

dependencies {
    mpsExtensions(libs.mps.extensions)
}

val extractMPS_extensions = tasks.register<Copy>("extractMPS_extensions") {
    from({ mpsExtensions.resolve().map { zipTree(it) } })
    into(myDependencyPath)
}

val setup = tasks.register("setup") {
    group = "Setup"
    description = "Download and extract the MPS dependencies of mps-project."
    dependsOn(extractMPS_extensions)
}

mpsEnvironments {
    // defaults for all environments: MPS of the version in gradle/libs.versions.toml
    // will be downloaded from Nexus via Gradle alongside matching JBR, moved to
    // .mpsconfig/mps-$VERSION
    mpsVersion.set(libs.versions.mps.get())
    // keep the prepared MPS in .mpsconfig/mps-<version>-<OS> instead of the shared cache folder (~/.cache/mps-env-gen)
    mpsInTargetPath.set(true)
    projectPath.set(project.layout.projectDirectory.dir("mps-project"))

    environments {
        register("0-default")

        register("1-special") {
            // start scripts to generate (default: current OS); scripts contain absolute paths of this machine
            os.set(listOf(OS.LINUX, OS.MAC, OS.WINDOWS))

            // any setting of the defaults can be overridden per environment, e.g. a locally installed MPS instead of
            // the download (a path takes precedence over mpsVersion):
            // mpsPath.set(file("/path/to/MPS-${libs.versions.mps.get()}"))
            // projectPath.set(file("/some/other/mps/project/path/"))

            // fixed node URL port; MPS does not start if it is in use (default: first free one from 63321 at each start)
            httpPort.set(63400)
            theme.set(Theme.LIGHT)                 // default: LIGHT
            xms.set("2048m")                       // default: 1024m
            xmx.set("4096m")                       // default: 2048m
            ratio.set(8)                           // default: 4
            modelCheckBeforeGeneration.set(false)  // default: false

            // additional lines of mps64.vmoptions and idea.properties
            vmOptions.addAll("# This is an extra line in the vmoptions file", "# And another ...")
            ideaProperties.add("# This is an extra line in the idea.properties file ...")
        }

        register("2-debug") {
            debug.set(true)          // default: false
            debugPort.set(1234)      // default: 5071
            debugSuspend.set(true)   // wait for a debugger (default: false)
            // for full control, leave debug off and add your own line: vmOptions.add("-agentlib:jdwp=...")
        }
    }
}

val doAll = tasks.register("doAll") {
    dependsOn(setup)
    dependsOn("generateMpsEnvironmentAll")
}
