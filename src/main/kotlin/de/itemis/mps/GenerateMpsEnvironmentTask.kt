package de.itemis.mps

import org.gradle.api.DefaultTask
import org.gradle.api.Incubating
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.gradle.util.internal.GFileUtils
import java.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.*

@Incubating
abstract class GenerateMpsEnvironmentTask : DefaultTask() {

    // the default folder will always be written - even if this is changed in the user configuration...
    @get:OutputDirectory
    val configBasePath: DirectoryProperty =
        project.objects.directoryProperty().convention(project.layout.buildDirectory)

    @Input
    val osToGenerate: ListProperty<Utils.OS> =
        project.objects.listProperty<Utils.OS>().convention(listOf(Utils.OS.LINUX))

    @get:Input
    val environmentName: Property<String> = project.objects.property<String>()

    @get:InputDirectory
    val mpsBasePath: DirectoryProperty = project.objects.directoryProperty()

    @get:InputDirectory
    val mpsProjectPath: DirectoryProperty = project.objects.directoryProperty()

    // vm args
    @get:Input
    val lightTheme: Property<Boolean> = project.objects.property<Boolean>()

    @get:Input
    val xms: Property<String> = project.objects.property<String>()

    @get:Input
    val xmx: Property<String> = project.objects.property<String>()

    @get:Input
    val ratioValue: Property<Int> = project.objects.property<Int>()

    @get:Input
    val extraVmArgs: ListProperty<String> = project.objects.listProperty<String>()

    @get:Input
    val debugEnable: Property<Boolean> = project.objects.property<Boolean>()

    @get:Input
    val debugPort: Property<Int> = project.objects.property<Int>()

    @get:Input
    val debugSuspend: Property<Boolean> = project.objects.property<Boolean>()

    // idea args
    @get:Input
    val extraIdeaArgs: ListProperty<String> = project.objects.listProperty<String>()


    // TODO document assumptions:
    //  - generic MPS
    //  - downloaded via gradle extension
    //  -

    // TODO:
    //  - overwrite on regen? avoids deleting 'changes' etc.

    @TaskAction
    fun action() {

        val currentEnvironmentName: String = environmentName.get()
        val currentDate: String = SimpleDateFormat("dd/M/yyyy hh:mm:ss").format(Calendar.getInstance().time).toString()
        val currentVersion: String = GenerateMpsEnvironmentTask::class.java.getPackage().implementationVersion
        val currentConfigPath = configBasePath.dir(environmentName).get()
        val currentMpsConfigPath = configBasePath.dir("${environmentName.get()}/mps").get()
        val currentMpsPath = mpsBasePath.get().toString()

        println("---------------------------------")
        println("Running env generator version $currentVersion")
        println("Current dat: $currentDate")
        println("Writing start scripts to ${currentConfigPath.asFile} ")
        println("Writing MPS config files to ${currentMpsConfigPath.asFile} ")
        println("OS: $osToGenerate")
        println("---------------------------------")

        // target to where this config is written
        GFileUtils.mkdirs(currentConfigPath.asFile)
        GFileUtils.mkdirs(currentMpsConfigPath.asFile)


        // write the README file
        currentConfigPath.file(Constants.README_FILENAME).asFile.writeText(
            MessageFormat.format(
                javaClass.getResource(Constants.README_TEMPLATE_PATH)!!.readText(),
                osToGenerate.get().joinToString { " - ${it.name}\n" },
                currentDate,
                GenerateMpsEnvironmentTask::class.java.getPackage().implementationVersion
            )
        )

        // write MPS vm options file
        currentMpsConfigPath.file(Constants.MPS_VMOPTIONS_FILENAME).asFile.writeText(
            StringBuilder(
                javaClass.getResource(Constants.MPS_VMOPTIONS_TEMPLATE_PATH)!!.readText()
                    .replace("GENERATION_DATE", currentDate)
                    .replace("<XMX_VALUE>", xmx.get())
                    .replace("<XMS_VALUE>", xms.get())
                    .replace("<RATIO_VALUE>", "${ratioValue.get()}")
                    .replace("DEBUGENABLE", if (debugEnable.get()) "" else "#")
                    .replace("DEBUGPORT", "${debugPort.get()}")
                    .replace("SUSPEND", if (debugSuspend.get()) "y" else "n")
            )
                // add all user arguments
                .append(extraVmArgs.get().joinToString("\n")).toString()
        )

        // write idea properties file
        currentMpsConfigPath.file(Constants.IDEA_PROPERTIES_FILENAME).asFile.writeText(
            StringBuilder(
                javaClass.getResource(Constants.IDEA_PROPERTIES_TEMPLATE_PATH)!!.readText()
                    .replace("REPLACE_ME__GENERATION_DATE", currentDate)
                    .replace("REPLACE_ME__VERSION", currentVersion)
                    .replace("REPLACE_ME__CONFIG_PATH_CONFIG", currentMpsConfigPath.dir("config").toString())
                    .replace("REPLACE_ME__CONFIG_PATH_SYSTEM", currentMpsConfigPath.dir("system").toString())
                    .replace("REPLACE_ME__CONFIG_PATH_SCRATCH", currentMpsConfigPath.dir("scratch").toString())
                    .replace("REPLACE_ME__CONFIG_PATH_PLUGINS", currentMpsConfigPath.dir("plugins").toString())
                    .replace("REPLACE_ME__CONFIG_PATH_LOG", currentMpsConfigPath.dir("log").toString())
            )
                // add all user arguments
                .append(extraIdeaArgs.get().joinToString("\n")).toString()
        )

        if (lightTheme.get()) {
            val optionsPath = currentMpsConfigPath.dir("config/options")
            GFileUtils.mkdirs(optionsPath.asFile)
            currentMpsConfigPath.dir(optionsPath.toString()).file("laf.xml").asFile.writeText(
                javaClass.getResource(Constants.MPS_CONFIG_TEMPLATE_PATH + "/laf.xml")!!.readText()
            )
            currentMpsConfigPath.dir(optionsPath.toString()).file("colors.scheme.xml").asFile.writeText(
                javaClass.getResource(Constants.MPS_CONFIG_TEMPLATE_PATH + "/colors.scheme.xml")!!.readText()
            )
        }

        // trust the mps project path
        if (true) {
            val optionsPath = currentMpsConfigPath.dir("config/options")
            GFileUtils.mkdirs(optionsPath.asFile)
            currentMpsConfigPath.dir(optionsPath.toString()).file("trusted-paths.xml").asFile.writeText(
                javaClass.getResource(Constants.MPS_CONFIG_TEMPLATE_PATH + "/trusted-paths.xml")!!.readText()
                    .replace("PARENT_PATH_TO_TRUST", mpsProjectPath.get().dir("..").asFile.toString())
                    .replace("PATH_TO_TRUST", mpsProjectPath.asFile.get().toString())
            )
        }
        // automatically open project
        if (true) {
            val optionsPath = currentMpsConfigPath.dir("config/options")
            GFileUtils.mkdirs(optionsPath.asFile)
            currentMpsConfigPath.dir(optionsPath.toString()).file("recentProjects.xml").asFile.writeText(
                javaClass.getResource(Constants.MPS_CONFIG_TEMPLATE_PATH + "/recentProjects.xml")!!.readText()
                    .replace("PATH_TO_PROJECT", mpsProjectPath.asFile.get().toString())
                    .replace("PROJECT_TITLE", mpsProjectPath.file(".mps/.name").get().asFile.readText())
            )
        }
        // disable tips on startup
        if (true) {
            val optionsPath = currentMpsConfigPath.dir("config/options")
            GFileUtils.mkdirs(optionsPath.asFile)
            currentMpsConfigPath.dir(optionsPath.toString()).file("ide.general.xml").asFile.writeText(
                javaClass.getResource(Constants.MPS_CONFIG_TEMPLATE_PATH + "/ide.general.xml")!!.readText()
            )
        }

        // OS specific files ...
        osToGenerate.get().forEach { currOS ->
            when (currOS) {
                /////////////////////////////////////////////////////////////////////////////////////////////////
                Utils.OS.LINUX -> {
                    logger.warn("Generating linux start scripts for $currentEnvironmentName ...")

                    // sometimes when we obtain MPS via gradle, the sh file is missing +x. These calls return false if they don't work
                    mpsBasePath.dir("bin/mps.sh").get().asFile.setExecutable(true)
                    mpsBasePath.file("bin/linux/fsnotifier").get().asFile.setExecutable(true)
                    mpsBasePath.file("bin/linux/restart.py").get().asFile.setExecutable(true)

                    // write startup file
                    val shFileName = currentConfigPath.file(
                        MessageFormat.format(
                            Constants.MPS_RUN_SCRIPT_LINUX_FILENAME,
                            currentEnvironmentName
                        )
                    )

                    shFileName.asFile.writeText(
                        javaClass.getResource(Constants.MPS_RUN_SCRIPT_LINUX_TEMPLATE_PATH)!!.readText()
                            .replace("REPLACE_ME__GENERATION_DATE", currentDate)
                            .replace("REPLACE_ME__VERSION", currentVersion)
                            .replace("REPLACE_ME__CONFIG_PATH", currentConfigPath.toString())
                            .replace("REPLACE_ME__CONFIG_MPS_PATH", currentMpsConfigPath.toString())
                            .replace("REPLACE_ME__MPS_PATH", currentMpsPath)
                            .replace("REPLACE_ME__CONFIG_TMUX_SESSION_NAME", currentEnvironmentName)
                    )
                    shFileName.asFile.setExecutable(true)
                }

                /////////////////////////////////////////////////////////////////////////////////////////////////
                Utils.OS.WINDOWS -> {
                    logger.warn("Generating win start scripts for $currentEnvironmentName ...")

                    // write startup file
                    val batFileName = currentConfigPath.file(
                        MessageFormat.format(
                            Constants.MPS_RUN_SCRIPT_WIN_FILENAME,
                            currentEnvironmentName
                        )
                    )

                    batFileName.asFile.writeText(
                        javaClass.getResource(Constants.MPS_RUN_SCRIPT_WIN_TEMPLATE_PATH)!!.readText()
                            .replace("GENERATION_DATE", currentDate)
                            .replace("ENVIRONMENT_NAME", environmentName.get())
                            .replace("CONFIG_MPS_PATH", mpsBasePath.get().toString())
                            .replace("CONFIG_BASE_PATH", configBasePath.dir(environmentName).toString())
                    )
                    batFileName.asFile.setExecutable(true)
                }

                /////////////////////////////////////////////////////////////////////////////////////////////////
                Utils.OS.MAC -> logger.error("Mac not implemented") // TODO

                /////////////////////////////////////////////////////////////////////////////////////////////////
                Utils.OS.OTHER -> logger.error("OS not supported")
            }
        }
    }
}