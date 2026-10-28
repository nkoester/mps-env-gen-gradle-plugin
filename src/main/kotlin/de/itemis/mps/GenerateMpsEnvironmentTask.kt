package de.itemis.mps

import org.gradle.api.DefaultTask
import org.gradle.api.Incubating
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.gradle.util.internal.GFileUtils
import java.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.text.replace

@Incubating
abstract class GenerateMpsEnvironmentTask : DefaultTask() {

    // the default folder will always be written - even if this is changed in the user configuration...
    @get:OutputDirectory
    val configBasePath: DirectoryProperty = project.objects.directoryProperty()

    @Input
    val osToGenerate: ListProperty<Utils.OS> = project.objects.listProperty<Utils.OS>()

    @get:Input
    val environmentName: Property<String> = project.objects.property<String>()

    @get:InputDirectory
    val mpsPath: DirectoryProperty = project.objects.directoryProperty()

    @get:InputDirectory
    @Optional
    val javaHome: DirectoryProperty = project.objects.directoryProperty()

    @get:InputDirectory
    val mpsProjectPath: DirectoryProperty = project.objects.directoryProperty()

    // vm args
    @get:Input
    val disableModelCheckBeforeGeneration: Property<Boolean> = project.objects.property<Boolean>()

    @get:Input
    val lightTheme: Property<Boolean> = project.objects.property<Boolean>()

    @get:Input
    val xms: Property<String> = project.objects.property<String>()

    @get:Input
    val xmx: Property<String> = project.objects.property<String>()

    @get:Input
    val ratioValue: Property<Int> = project.objects.property<Int>()

    @get:Input
    val httpPort: Property<Int> = project.objects.property<Int>()

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
    //  - runnable MPS (!)

    // TODO:
    //  - overwrite on regen? avoids deleting 'changes' etc.

    @TaskAction
    fun action() {

        val currentEnvironmentName: String = environmentName.get()
        val currentDate: String = SimpleDateFormat("dd/M/yyyy HH:mm:ss").format(Calendar.getInstance().time).toString()
        val currentVersion: String = GenerateMpsEnvironmentTask::class.java.getPackage().implementationVersion ?: "unknown"
        val currentConfigPath = configBasePath.dir(environmentName).get()
        val currentMpsConfigPath = configBasePath.dir("${environmentName.get()}/mps").get()
        val currentMpsPath = mpsPath.get().toString()
        val currentJavaHome = if (javaHome.isPresent) javaHome.get().toString() else ""

        logger.lifecycle("Running env generator version $currentVersion")
        logger.info("Current date: $currentDate")
        logger.info("Writing start scripts to ${currentConfigPath.asFile} ")
        logger.info("Writing MPS config files to ${currentMpsConfigPath.asFile} ")
        logger.info("OS: ${osToGenerate.get()}")

        // target to where this config is written
        GFileUtils.mkdirs(currentConfigPath.asFile)
        GFileUtils.mkdirs(currentMpsConfigPath.asFile)

        // write the README file
        currentConfigPath.file(Constants.README_FILENAME).asFile.writeText(
            MessageFormat.format(
                readTemplate(Constants.README_TEMPLATE_PATH),
                osToGenerate.get().joinToString(separator = "") { " - ${it.name}\n" },
                currentDate,
                currentVersion
            )
        )

        // write MPS vm options file
        currentMpsConfigPath.file(Constants.MPS_VMOPTIONS_FILENAME).asFile.writeText(
            StringBuilder(
                readTemplate(Constants.MPS_VMOPTIONS_TEMPLATE_PATH)
                    .replace("REPLACE_ME__GENERATION_DATE", currentDate)
                    .replace("REPLACE_ME__VERSION", currentVersion)
                    .replace("REPLACE_ME__XMX_VALUE", xmx.get())
                    .replace("REPLACE_ME__XMS_VALUE", xms.get())
                    .replace("REPLACE_ME__RATIO_VALUE", "${ratioValue.get()}")
                    .replace("REPLACE_ME__HTTP_PORT", "${httpPort.get()}")
                    .replace("REPLACE_ME__DEBUGENABLE", if (debugEnable.get()) "" else "#")
                    .replace("REPLACE_ME__DEBUGPORT", "${debugPort.get()}")
                    .replace("REPLACE_ME__SUSPEND", if (debugSuspend.get()) "y" else "n")
            )
                // add all user arguments
                .append(extraVmArgs.get().joinToString("\n")).toString()
        )

        val optionsPath = currentMpsConfigPath.dir("config/options")
        GFileUtils.mkdirs(optionsPath.asFile)

        if (disableModelCheckBeforeGeneration.get()) {
            optionsPath.file("generationSettings.xml").asFile.writeText(
                readTemplate("${Constants.MPS_CONFIG_TEMPLATE_PATH}/generationSettings.xml")
            )
        }

        val (lafLine, colorSchemeName) = if (lightTheme.get()) {
            "<laf class-name=\"com.intellij.ide.ui.laf.IntelliJLaf\" themeId=\"JetBrainsLightTheme\" />" to "IntelliJ Light"
        } else {
            "<laf class-name=\"com.intellij.ide.ui.laf.darcula.DarculaLaf\" />" to "Darcula"
        }
        optionsPath.file("laf.xml").asFile.writeText(
            readTemplate("${Constants.MPS_CONFIG_TEMPLATE_PATH}/laf.xml").replace("LAF_LINE", lafLine)
        )
        optionsPath.file("colors.scheme.xml").asFile.writeText(
            readTemplate("${Constants.MPS_CONFIG_TEMPLATE_PATH}/colors.scheme.xml").replace("COLOR_SCHEME_NAME", colorSchemeName)
        )

        // trust the mps project path
        val projectDir = mpsProjectPath.get().asFile
        optionsPath.file("trusted-paths.xml").asFile.writeText(
            readTemplate("${Constants.MPS_CONFIG_TEMPLATE_PATH}/trusted-paths.xml")
                .replace("PARENT_PATH_TO_TRUST", projectDir.absoluteFile.parentFile?.toString() ?: projectDir.toString())
                .replace("PATH_TO_TRUST", projectDir.toString())
        )

        // automatically open project; fall back to the folder name if the project has no .mps/.name
        val projectNameFile = projectDir.resolve(".mps/.name")
        val projectTitle = if (projectNameFile.isFile) projectNameFile.readText().trim() else projectDir.name
        optionsPath.file("recentProjects.xml").asFile.writeText(
            readTemplate("${Constants.MPS_CONFIG_TEMPLATE_PATH}/recentProjects.xml")
                .replace("PATH_TO_PROJECT", projectDir.toString())
                .replace("PROJECT_TITLE", projectTitle)
        )

        // disable tips on startup
        optionsPath.file("ide.general.xml").asFile.writeText(
            readTemplate("${Constants.MPS_CONFIG_TEMPLATE_PATH}/ide.general.xml")
        )

        // idea.properties is shared by all OS scripts of this environment, so write it once
        writeIdeaProperties(currentMpsConfigPath, currentDate, currentVersion)

        // OS specific files ...
        osToGenerate.get().forEach { currentOs ->
            when (currentOs) {
                /////////////////////////////////////////////////////////////////////////////////////////////////
                Utils.OS.LINUX -> {
                    logger.lifecycle("Generating linux start scripts for $currentEnvironmentName ...")

                    // sometimes when we obtain MPS via gradle, the sh file is missing +x. These calls return false if they don't work
                    mpsPath.file("bin/mps.sh").get().asFile.setExecutable(true)
                    mpsPath.file("bin/linux/fsnotifier").get().asFile.setExecutable(true)
                    mpsPath.file("bin/linux/restart.py").get().asFile.setExecutable(true)

                    writeStartScriptPOSIX(
                        currentConfigPath,
                        currentEnvironmentName,
                        currentDate,
                        currentVersion,
                        currentMpsConfigPath,
                        currentMpsPath,
                        currentJavaHome,
                        currentOs.toString(),
                        Constants.MPS_RUN_SCRIPT_LINUX_FILENAME,
                        Constants.MPS_RUN_SCRIPT_LINUX_TEMPLATE_PATH
                    )
                }

                /////////////////////////////////////////////////////////////////////////////////////////////////
                Utils.OS.WINDOWS -> {
                    logger.lifecycle("Generating WIN start scripts for $currentEnvironmentName ...")

                    // write startup file
                    val batFileName = currentConfigPath.file(
                        MessageFormat.format(
                            Constants.MPS_RUN_SCRIPT_WIN_FILENAME,
                            currentOs.toString(),
                            currentEnvironmentName
                        )
                    )

                    batFileName.asFile.writeText(
                        readTemplate(Constants.MPS_RUN_SCRIPT_WIN_TEMPLATE_PATH)
                            .replace("REPLACE_ME__GENERATION_DATE", currentDate)
                            .replace("REPLACE_ME__CONFIG_MPS_PATH", currentMpsConfigPath.toString())
                            .replace("REPLACE_ME__MPS_PATH", currentMpsPath)
                            .run{ if (!currentJavaHome.isNullOrEmpty()){
                                replace("REPLACE_ME__JAVA_HOME", currentJavaHome)
                            } else toString() }
                    )
                    batFileName.asFile.setExecutable(true)
                }

                /////////////////////////////////////////////////////////////////////////////////////////////////
                Utils.OS.MAC -> {
                    logger.lifecycle("Generating OSX start scripts for $currentEnvironmentName ...")

                    writeStartScriptPOSIX(
                        currentConfigPath,
                        currentEnvironmentName,
                        currentDate,
                        currentVersion,
                        currentMpsConfigPath,
                        currentMpsPath,
                        currentJavaHome,
                        currentOs.toString(),
                        Constants.MPS_RUN_SCRIPT_MAC_FILENAME,
                        Constants.MPS_RUN_SCRIPT_MAC_TEMPLATE_PATH
                    )
                }

                /////////////////////////////////////////////////////////////////////////////////////////////////
                Utils.OS.OTHER -> logUnsupportedOs()
                null -> logUnsupportedOs()
            }
        }
    }

    private fun logUnsupportedOs() {
        logger.error("Environment ${environmentName.get()} Generation: Only Linux/Win/OSX are supported.")
    }

    private fun writeStartScriptPOSIX(
        currentConfigPath: Directory,
        currentEnvironmentName: String,
        currentDate: String,
        currentVersion: String,
        currentMpsConfigPath: Directory,
        currentMpsPath: String,
        currentJavaHome: String,
        osType: String,
        filename: String,
        templatePath: String,
    ) {
        // write startup file
        val shFileName = currentConfigPath.file(
            MessageFormat.format(
                filename,
                osType,
                currentEnvironmentName
            )
        )

        shFileName.asFile.writeText(
            readTemplate(templatePath)
                .replace("REPLACE_ME__GENERATION_DATE", currentDate)
                .replace("REPLACE_ME__VERSION", currentVersion)
                .replace("REPLACE_ME__CONFIG_PATH", currentConfigPath.toString())
                .replace("REPLACE_ME__CONFIG_MPS_PATH", currentMpsConfigPath.toString())
                .replace("REPLACE_ME__MPS_PATH", currentMpsPath)
                .replace("REPLACE_ME__CONFIG_TMUX_SESSION_NAME", currentEnvironmentName)
                .run{ if (!currentJavaHome.isNullOrEmpty()){
                    replace("REPLACE_ME__JAVA_HOME", currentJavaHome)
                } else toString() }
        )
        shFileName.asFile.setExecutable(true)
    }

    private fun readTemplate(path: String): String =
        requireNotNull(javaClass.getResource(path)) { "Missing template resource '$path'" }.readText()

    private fun writeIdeaProperties(
        currentMpsConfigPath: Directory,
        currentDate: String,
        currentVersion: String
    ) {
        currentMpsConfigPath.file(Constants.IDEA_PROPERTIES_FILENAME).asFile.writeText(
            StringBuilder(
                readTemplate(Constants.IDEA_PROPERTIES_TEMPLATE_PATH)
                    .replace("REPLACE_ME__GENERATION_DATE", currentDate)
                    .replace("REPLACE_ME__VERSION", currentVersion)
                    .replace("REPLACE_ME__CONFIG_PATH_CONFIG", currentMpsConfigPath.dir("config").toString())
                    .replace("REPLACE_ME__CONFIG_PATH_SYSTEM", currentMpsConfigPath.dir("system").toString())
                    .replace("REPLACE_ME__CONFIG_PATH_SCRATCH", currentMpsConfigPath.dir("scratch").toString())
                    .replace("REPLACE_ME__CONFIG_PATH_PLUGINS", currentMpsConfigPath.dir("plugins").toString())
                    .replace("REPLACE_ME__CONFIG_PATH_LOG", currentMpsConfigPath.dir("log").toString())
                    // properties format: backslashes (Windows paths) must be escaped; no-op for POSIX paths
                    .replace("\\", "\\\\")
            )
                // add all user arguments
                .append(extraIdeaArgs.get().joinToString("\n")).toString()
        )
    }
}