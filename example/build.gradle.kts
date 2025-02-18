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
val myMpsPath = Path(project.layout.buildDirectory.get().toString(), "mps")

dependencies {
    mps("com.jetbrains:mps:2022.2.4")
}
val extractMps by tasks.registering(Copy::class) {
    from({ mps.resolve().map { zipTree(it) } })
    into(myMpsPath)
}


// TODO: JDK selection

val pathToYourMPSInstallation = Path("/vol/mps/MPS-2022.2.4-linux/").toFile()
//val pathToYourMPSInstallation = Path("C:/Users/nkoester/Desktop/mps").toFile()


// the environments block allows you to define your desired
// MPS environments you want to create
mpsEnvironments {
    // mpsBasePath - home of the MPS you want to use for the environments
    //               (no default; can be overwritten in the environments by
    //               setting mpsPathLocal)
    mpsPath.set(pathToYourMPSInstallation)
    //               if you get your MPS via gradle, you can use it!
//    mpsPath.set(project.layout.buildDirectory.dir("mps"))

    // targetPath - refers to the folder where environments are generated into (default: '.mpsconfig')
    targetPath.set(project.layout.projectDirectory.dir(".mpsconfig"))

    // mpsProjectPath - the path where your MPS project is at (no default)
    //                  (no default; can be overwritten in each environment by
    //                  setting mpsProjectPathLocal)
    mpsProjectPath.set(project.layout.projectDirectory.dir("mps-project"))

    // most simple way to create a new environment named 'default'
    environment("0-default"){}

    environment("1-special") {
        // osToGenerate - specifies for which OS to generate a startup script
        osToGenerate = listOf<Utils.OS>(Utils.OS.LINUX)

        // you can overwrite the mpsPath and use any other MPS installation
        mpsPathLocal.set(pathToYourMPSInstallation)

        // we could overwrite the project path for this environment if we wanted to
        // mpsProjectPath.set(File("/some/other/mps/project/path/"))

        // set certain MPS specific settings you would normally set in the mps64.vmoptions file
        mpsSettings {
            // lightTheme - if you really want to, you can use the dark theme (default: true)
            lightTheme.set(false)
            // set some important settings for MPS:
            // xms   - 1024m
            // xmx   - 2048m
            // ratio - default: 4
            xms.set("2048m")
            xmx.set("4096m")
            ratio.set(8)

            // extraVmmArgs - allows to set anything extra. you can overwrite anything with this
            extraVmmArgs.set(
                project.objects.listProperty<String>().convention(
                    listOf(
                        "# This is an extra line in the vmargs file",
                        "# And another ..."
                    )
                )
            )
        }

        // set certain IDEA specific settings for this environment
        ideaSettings {
            // anything else you would normally set in the idea.properties file
            extraIdeaArgs.set(
                listOf("# This is an extra line in the idea.prooperties file ...")
            )
        }
    }

    environment("2-debug") {
        mpsSettings {
            // debugEnabled - enable debugging for this environment
            //                (default: false)
            debugEnabled = true

            // debugPort - enable debugging for this environment
            //             (default: 5071)
            debugPort = 1234

            // debugSuspend - suspend until debugger is connected to MPS
            //                (default: false)
            debugSuspend = true

            // if you want more control over debugging, set debugEnabled to false and use
            // extraVmmArgs to add your own debugging line
            // extraVmmArgs.set(
            //    project.objects.listProperty<String>().convention(
            //        listOf(
            //            "-myHighly,specific-debugging,line",
            //        )
            //    )
            //)
        }
    }
}
