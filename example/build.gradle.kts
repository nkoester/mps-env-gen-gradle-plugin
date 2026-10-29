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

val myMpsPath = Path(project.layout.buildDirectory.get().toString(), "mps")
val myDependencyPath = Path(project.layout.buildDirectory.get().toString(), "dependencies")

val mps: Configuration = configurations.create("mps")
val mpsExtensions: Configuration = configurations.create("mpsExtensions")

dependencies {
    mps(libs.mps)
    mpsExtensions(libs.mps.extensions)
}

val extractMps = tasks.register<Copy>("extractMps") {
    from({ mps.resolve().map { zipTree(it) } })
    into(myMpsPath)
}
val extractMPS_extensions = tasks.register<Copy>("extractMPS_extensions") {
    from({ mpsExtensions.resolve().map { zipTree(it) } })
    into(myDependencyPath)
}

val setup = tasks.register("setup") {
    group = "Setup"
    description = "Download and extract MPS and the all MPS dependencies."
    dependsOn(extractMps)
    dependsOn(extractMPS_extensions)
}





val pathToYourMPSInstallation = Path("/vol/mps/MPS-2022.2.4-linux/").toFile()

mpsEnvironments {
    // defaults for all environments; use mpsVersion.set("2025.3") instead to download MPS from Nexus
    mpsPath.set(pathToYourMPSInstallation)
    projectPath.set(project.layout.projectDirectory.dir("mps-project"))

    environments {
        register("0-default")

        register("1-special") {
            // start scripts to generate (default: current OS); scripts contain absolute paths of this machine
            os.set(listOf(OS.LINUX, OS.MAC, OS.WINDOWS))

            // any setting of the defaults can be overridden per environment
            mpsPath.set(pathToYourMPSInstallation)
            // projectPath.set(file("/some/other/mps/project/path/"))

            httpPort.set(9292)
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
            httpPort.set(9293)
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
