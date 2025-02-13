import de.itemis.mps.Utils
import kotlin.io.path.Path

plugins {
  id("de.itemis.mps.environment-generator") version "1.1+"
}

repositories {
   // itemis nexus
    maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
    mavenLocal()
}

val mps: Configuration by configurations.creating
val mpsPath = Path(project.layout.buildDirectory.get().toString(), "mps")

dependencies {
    mps("com.jetbrains:mps:2022.2.4")
}
val extractMps by tasks.registering(Copy::class) {
    from({ mps.resolve().map { zipTree(it) } })
    into(mpsPath)
}


mpsEnvironments {
    mpsBasePath.set(project.layout.buildDirectory.dir("mps"))
//    targetPath.set(project.layout.projectDirectory.dir(".mpsconfig"))

    environment("default") {
//         osStartupScriptsToGenerate = listOf<Utils.OS>(Utils.OS.LINUX, Utils.OS.WINDOWS, Utils.OS.MAC)

        mpsSettings {
            mpsProjectPath.set(project.layout.projectDirectory.dir("mps"))
            lightTheme.set(true)
            xms.set("1024m")
            xmx.set("2048m")
            ratio.set(3)
            extraVmmArgs.set(project.objects.listProperty<String>().convention(listOf("# This is an extra line in the vmargs file")))

            // TODO?
            //        welcomeMessage = false
            //        openProjectByDefault = true
            // extraGlobalLibraries = "some/paths/here/?"

        }
        ideaSettings {
            extraIdeaArgs.set(listOf("# This is an extra line in the idea.prooperties file ..."))
        }
    }

    environment("debugTest") {
        mpsSettings {
            debugEnabled = true
            //            transport = "dt_socket"
            debugPort = 1234
            debugSuspend = true
        }

    }
}

//mpsRunEnvironment {
