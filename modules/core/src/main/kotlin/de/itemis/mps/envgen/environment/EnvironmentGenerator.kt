package de.itemis.mps.envgen.environment

import de.itemis.mps.envgen.Constants
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.Arch
import de.itemis.mps.envgen.distribution.DistributionLayout
import de.itemis.mps.envgen.distribution.Platform
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.MessageFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.text.SimpleDateFormat
import java.util.Calendar

/** everything needed to write one isolated MPS environment */
data class EnvironmentSpec(
    /** directory holding all environments, e.g. `.mpsconfig` */
    val targetPath: File,
    val environmentName: String,
    val os: List<OS>,
    /** MPS home: contains `bin/` (on macOS `MPS.app/Contents` or the app bundle itself) */
    val mpsPath: File,
    val javaHome: File? = null,
    /** MPS project to trust and open; null generates an environment without project */
    val projectPath: File? = null,
    /** MPS checks models before generation; slow, so off by default */
    val modelCheckBeforeGeneration: Boolean = false,
    val theme: Theme = Theme.LIGHT,
    val xms: String = "1024m",
    val xmx: String = "2048m",
    val ratio: Int = 4,
    /** first port for opening nodes from URLs; the start scripts take the next free one */
    val httpPort: Int = 63321,
    val vmOptions: List<String> = emptyList(),
    val debug: Boolean = false,
    val debugPort: Int = 5071,
    val debugSuspend: Boolean = false,
    val ideaProperties: List<String> = emptyList(),
    /** printed by the start scripts if MPS is missing */
    val missingMpsHint: String,
    /** version of the generating tool, written into the generated files */
    val generatorVersion: String,
    /** how [mpsPath] was obtained; recorded so the environment can be refreshed without the original arguments */
    val mpsOrigin: MpsOrigin = MpsOrigin.PATH,
    /** MPS version or path as given by the user */
    val mpsSource: String = mpsPath.toString(),
    val jbrVersion: String? = null,
) {
    val environmentDir: File get() = File(targetPath, environmentName)
    val mpsConfigDir: File get() = File(environmentDir, "mps")
}

enum class Theme { LIGHT, DARK }

enum class MpsOrigin {
    /** explicit installation path */
    PATH,
    /** installation found below a base path of local installations */
    LOCAL,
    /** prepared from the Nexus artifacts */
    NEXUS,
}

/** the files of an environment */
enum class EnvironmentPart {
    /** `README.md` */
    README,
    /** `startMPS_<OS>_<name>.sh|bat` */
    START_SCRIPTS,
    /** `environment.properties` */
    METADATA,
    /** `mps/mps64.vmoptions` */
    VM_OPTIONS,
    /** `mps/idea.properties`; its path keys always follow the location */
    IDEA_PROPERTIES,
    /** `laf.xml`, `colors.scheme.xml`, `ui.lnf.xml` and `early-access-registry.txt` (new UI) */
    THEME,
    /** `trusted-paths.xml`, `recentProjects.xml` */
    PROJECT,
    /** `generationSettings.xml` */
    MODEL_CHECK,
    /** `ide.general.xml` */
    TIPS,
    /** `updates.xml` (no "what's new"), `other.xml` (new UI onboarding seen) */
    ONBOARDING;

    companion object {
        val ALL: Set<EnvironmentPart> = entries.toSet()

        /**
         * written only if missing; afterwards they belong to the user (MPS settings, hand edits) and are rewritten
         * only on explicit request
         */
        val USER_OWNED: Set<EnvironmentPart> = setOf(VM_OPTIONS, IDEA_PROPERTIES, THEME, PROJECT, MODEL_CHECK, TIPS, ONBOARDING)
    }
}

/** writes environments; shared by the Gradle plugin and the CLI */
class EnvironmentGenerator(
    private val lifecycle: (String) -> Unit = {},
    private val info: (String) -> Unit = {},
    private val error: (String) -> Unit = lifecycle,
    private val warn: (String) -> Unit = lifecycle,
    /** how to overwrite user owned files with this tool, used in hints */
    private val overwriteHint: String = "overwrite them explicitly",
) {

    /**
     * writes the environment; user owned files ([EnvironmentPart.USER_OWNED]) only if missing
     *
     * @param parts files to consider
     * @param overwrite user owned parts to rewrite although they exist; previous files go to `.backup/<timestamp>`
     * @param history event recorded in the metadata, e.g. `copied from ...`
     */
    fun generate(
        spec: EnvironmentSpec,
        parts: Set<EnvironmentPart> = EnvironmentPart.ALL,
        overwrite: Set<EnvironmentPart> = emptySet(),
        history: String? = null,
    ) {
        val date = currentDate()
        lifecycle("Running env generator version ${spec.generatorVersion}")
        info("Current date: $date")
        info("Writing start scripts to ${spec.environmentDir}")
        info("Writing MPS config files to ${spec.mpsConfigDir}")
        info("OS: ${spec.os}")

        val stored = EnvironmentMetadata.read(spec.environmentDir, spec)?.spec
        spec.environmentDir.mkdirs()
        spec.mpsConfigDir.mkdirs()
        writeCreationTimestamp(spec)

        val files = UserFiles(spec, parts, overwrite)
        val build = MpsBuild.of(spec.mpsPath)
        info("MPS build: ${build ?: "unknown"}, new UI: ${build?.newUi ?: false}")

        if (EnvironmentPart.README in parts) {
            File(spec.environmentDir, Constants.README_FILENAME).writeText(
                MessageFormat.format(
                    readTemplate(Constants.README_TEMPLATE_PATH),
                    spec.os.joinToString(separator = "") { " - ${it.name}\n" },
                    date,
                    spec.generatorVersion,
                )
            )
        }
        files.write(EnvironmentPart.VM_OPTIONS, File(spec.mpsConfigDir, Constants.MPS_VMOPTIONS_FILENAME)) { vmOptions(spec, date) }
        writeIdeaProperties(spec, date, files)
        writeOptions(spec, build, files)
        if (EnvironmentPart.START_SCRIPTS in parts) writeStartScripts(spec, date)

        files.backupDir?.let { lifecycle("Previous files: $it") }
        val effective = stored?.let { files.kept.fold(spec) { s, part -> keepStored(s, it, part) } } ?: spec
        stored?.let { hintKeptSettings(spec, it, files.kept) }
        if (EnvironmentPart.METADATA in parts) EnvironmentMetadata.write(effective, history)
    }

    /** only rewrites the start scripts of an existing environment, keeping its configuration */
    fun refreshStartScripts(spec: EnvironmentSpec) {
        require(spec.environmentDir.isDirectory) { "Environment ${spec.environmentDir} does not exist" }
        generate(spec, setOf(EnvironmentPart.START_SCRIPTS, EnvironmentPart.METADATA))
    }

    /** write-once handling of user owned files; backups before explicit overwrites */
    private inner class UserFiles(val spec: EnvironmentSpec, val parts: Set<EnvironmentPart>, val overwrite: Set<EnvironmentPart>) {
        val kept = mutableSetOf<EnvironmentPart>()
        var backupDir: File? = null
            private set
        private val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

        fun write(part: EnvironmentPart, file: File, content: () -> String) {
            if (part !in parts) return
            if (file.exists() && part !in overwrite) {
                kept += part
                info("Kept $file")
                return
            }
            if (file.exists()) backup(file)
            file.parentFile.mkdirs()
            file.writeText(content())
        }

        private fun backup(file: File) {
            val dir = backupDir ?: File(spec.environmentDir, ".backup/$timestamp").also { backupDir = it }
            val copy = File(dir, file.relativeTo(spec.environmentDir).path).apply { parentFile.mkdirs() }
            Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    /** settings of kept parts stay as last written, so the metadata describes the files */
    private fun keepStored(spec: EnvironmentSpec, stored: EnvironmentSpec, part: EnvironmentPart): EnvironmentSpec = when (part) {
        EnvironmentPart.VM_OPTIONS -> spec.copy(xms = stored.xms, xmx = stored.xmx, ratio = stored.ratio, vmOptions = stored.vmOptions)
        EnvironmentPart.IDEA_PROPERTIES -> spec.copy(ideaProperties = stored.ideaProperties)
        EnvironmentPart.THEME -> spec.copy(theme = stored.theme)
        EnvironmentPart.PROJECT -> spec.copy(projectPath = stored.projectPath)
        EnvironmentPart.MODEL_CHECK -> spec.copy(modelCheckBeforeGeneration = stored.modelCheckBeforeGeneration)
        else -> spec
    }

    /** a changed setting that does not reach its kept file would look like a bug, so it is reported */
    private fun hintKeptSettings(spec: EnvironmentSpec, stored: EnvironmentSpec, kept: Set<EnvironmentPart>) {
        val changes = kept.flatMap { part ->
            val pairs = when (part) {
                EnvironmentPart.VM_OPTIONS -> listOf("xms" to (spec.xms to stored.xms), "xmx" to (spec.xmx to stored.xmx),
                    "ratio" to (spec.ratio to stored.ratio), "vmOptions" to (spec.vmOptions to stored.vmOptions))
                EnvironmentPart.IDEA_PROPERTIES -> listOf("ideaProperties" to (spec.ideaProperties to stored.ideaProperties))
                EnvironmentPart.THEME -> listOf("theme" to (spec.theme to stored.theme))
                EnvironmentPart.PROJECT -> listOf("projectPath" to (spec.projectPath to stored.projectPath))
                EnvironmentPart.MODEL_CHECK -> listOf("modelCheckBeforeGeneration" to (spec.modelCheckBeforeGeneration to stored.modelCheckBeforeGeneration))
                else -> emptyList()
            }
            pairs.filter { (_, values) -> values.first != values.second }.map { (name, values) -> "$name = ${values.first}" }
        }
        if (changes.isNotEmpty()) {
            warn("Not applied to ${spec.environmentName}: ${changes.joinToString()}. The affected files exist and may contain " +
                "changes made in MPS; to apply the settings, $overwriteHint.")
        }
    }

    /** `created.txt`: when the environment was created; never rewritten, so regenerating keeps it */
    private fun writeCreationTimestamp(spec: EnvironmentSpec) {
        val file = File(spec.environmentDir, CREATED_FILE)
        if (!file.exists()) file.writeText(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString() + "\n")
    }

    private fun currentDate(): String = SimpleDateFormat("dd/M/yyyy HH:mm:ss").format(Calendar.getInstance().time)

    /** debugging is decided by the start scripts (configured or `-d`), so the jdwp line is written commented */
    private fun vmOptions(spec: EnvironmentSpec, date: String): String =
        StringBuilder(
            readTemplate(Constants.MPS_VMOPTIONS_TEMPLATE_PATH)
                .replace("REPLACE_ME__GENERATION_DATE", date)
                .replace("REPLACE_ME__VERSION", spec.generatorVersion)
                .replace("REPLACE_ME__XMX_VALUE", spec.xmx)
                .replace("REPLACE_ME__XMS_VALUE", spec.xms)
                .replace("REPLACE_ME__RATIO_VALUE", "${spec.ratio}")
                .replace("REPLACE_ME__HTTP_PORT", "${spec.httpPort}")
                .replace("REPLACE_ME__DEBUGENABLE", "#")
                .replace("REPLACE_ME__DEBUGPORT", "${spec.debugPort}")
                .replace("REPLACE_ME__SUSPEND", "n")
        )
            // add all user arguments
            .append(spec.vmOptions.joinToString("\n")).toString()

    private fun writeOptions(spec: EnvironmentSpec, build: MpsBuild?, files: UserFiles) {
        val options = File(spec.mpsConfigDir, "config/options")
        val templates = Constants.MPS_CONFIG_TEMPLATE_PATH
        val newUi = build?.newUi == true

        files.write(EnvironmentPart.MODEL_CHECK, File(options, "generationSettings.xml")) {
            val template = readTemplate("$templates/generationSettings.xml")
            if (spec.modelCheckBeforeGeneration) template.replace("value=\"false\"", "value=\"true\"") else template
        }

        val (lafLine, colorScheme) = when {
            newUi && spec.theme == Theme.LIGHT -> "<laf themeId=\"ExperimentalLightWithLightHeader\" />" to "IntelliJ Light"
            newUi -> "<laf themeId=\"ExperimentalDark\" />" to "Dark"
            spec.theme == Theme.LIGHT -> "<laf class-name=\"com.intellij.ide.ui.laf.IntelliJLaf\" themeId=\"JetBrainsLightTheme\" />" to "IntelliJ Light"
            else -> "<laf class-name=\"com.intellij.ide.ui.laf.darcula.DarculaLaf\" />" to "Darcula"
        }
        files.write(EnvironmentPart.THEME, File(options, "laf.xml")) { readTemplate("$templates/laf.xml").replace("LAF_LINE", lafLine) }
        files.write(EnvironmentPart.THEME, File(options, "colors.scheme.xml")) {
            readTemplate("$templates/colors.scheme.xml").replace("COLOR_SCHEME_NAME", colorScheme)
        }
        if (newUi) files.write(EnvironmentPart.THEME, File(options, "ui.lnf.xml")) { readTemplate("$templates/ui.lnf.xml") }
        // the new UI as previous state: without it the IDE sees a switch from the classic UI and resets the theme while
        // initializing it, which logs a cycle error up to 251 and deadlocks the start from 261 (MPS 2026.1) on
        if (newUi) files.write(EnvironmentPart.THEME, File(spec.mpsConfigDir, "config/early-access-registry.txt")) {
            "ide.experimental.ui\ntrue\n"
        }

        spec.projectPath?.let { projectDir ->
            // trust the mps project path
            files.write(EnvironmentPart.PROJECT, File(options, "trusted-paths.xml")) {
                readTemplate("$templates/trusted-paths.xml")
                    .replace("PARENT_PATH_TO_TRUST", projectDir.absoluteFile.parentFile?.toString() ?: projectDir.toString())
                    .replace("PATH_TO_TRUST", projectDir.toString())
            }
            // automatically open project; fall back to the folder name if the project has no .mps/.name
            val projectNameFile = File(projectDir, ".mps/.name")
            val projectTitle = if (projectNameFile.isFile) projectNameFile.readText().trim() else projectDir.name
            files.write(EnvironmentPart.PROJECT, File(options, "recentProjects.xml")) {
                readTemplate("$templates/recentProjects.xml")
                    .replace("PATH_TO_PROJECT", projectDir.toString())
                    .replace("PROJECT_TITLE", projectTitle)
            }
        }

        // disable tips on startup
        files.write(EnvironmentPart.TIPS, File(options, "ide.general.xml")) { readTemplate("$templates/ide.general.xml") }

        // no "what's new" page: older versions read updates.xml; 242+ compare whats.new.last.shown.version with the
        // page's year-release-eap-hash, so a stored year 9999 is always newer. On 242+ the new UI onboarding counts as seen
        files.write(EnvironmentPart.ONBOARDING, File(options, "updates.xml")) { readTemplate("$templates/updates.xml") }
        if (newUi) files.write(EnvironmentPart.ONBOARDING, File(options, "other.xml")) {
            readTemplate("$templates/other.xml").replace("MPS_BUILD", build!!.number)
        }
    }

    /** shared by all OS scripts; if it exists, only the path keys follow the location of the environment */
    private fun writeIdeaProperties(spec: EnvironmentSpec, date: String, files: UserFiles) {
        val dir = spec.mpsConfigDir
        val paths = linkedMapOf(
            "idea.config.path" to File(dir, "config"),
            "idea.system.path" to File(dir, "system"),
            "idea.scratch.path" to File(dir, "scratch"),
            "idea.plugins.path" to File(dir, "plugins"),
            "idea.log.path" to File(dir, "log"),
        ).mapValues { (_, file) -> file.toString().replace("\\", "\\\\") }
        val file = File(dir, Constants.IDEA_PROPERTIES_FILENAME)

        files.write(EnvironmentPart.IDEA_PROPERTIES, file) {
            StringBuilder(
                readTemplate(Constants.IDEA_PROPERTIES_TEMPLATE_PATH)
                    .replace("REPLACE_ME__GENERATION_DATE", date)
                    .replace("REPLACE_ME__VERSION", spec.generatorVersion)
                    .replace("REPLACE_ME__CONFIG_PATH_CONFIG", paths.getValue("idea.config.path"))
                    .replace("REPLACE_ME__CONFIG_PATH_SYSTEM", paths.getValue("idea.system.path"))
                    .replace("REPLACE_ME__CONFIG_PATH_SCRATCH", paths.getValue("idea.scratch.path"))
                    .replace("REPLACE_ME__CONFIG_PATH_PLUGINS", paths.getValue("idea.plugins.path"))
                    .replace("REPLACE_ME__CONFIG_PATH_LOG", paths.getValue("idea.log.path"))
            )
                // add all user arguments
                .append(spec.ideaProperties.joinToString("\n")).toString()
        }
        if (EnvironmentPart.IDEA_PROPERTIES in files.kept) {
            val lines = file.readLines().toMutableList()
            paths.forEach { (key, value) ->
                val index = lines.indexOfFirst { it.trimStart().startsWith("$key=") }
                if (index >= 0) lines[index] = "$key=$value" else lines += "$key=$value"
            }
            file.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    private fun writeStartScripts(spec: EnvironmentSpec, date: String) {
        // scripts of a former name (moved/renamed environment) or OS no longer generated
        val current = spec.os.filter { it != OS.OTHER }.map { startScriptName(it, spec.environmentName) }.toSet()
        spec.environmentDir.listFiles().orEmpty()
            .filter { it.isFile && START_SCRIPT.matches(it.name) && it.name !in current }
            .forEach { it.delete() }

        spec.os.forEach { os ->
            val template = when (os) {
                OS.LINUX -> ScriptTemplate.LINUX
                OS.WINDOWS -> ScriptTemplate.WINDOWS
                OS.MAC -> ScriptTemplate.MAC
                else -> return@forEach error("Environment ${spec.environmentName} Generation: Only Linux/Win/OSX are supported.")
            }
            lifecycle("Generating ${os.name} start script for ${spec.environmentName} ...")

            if (os == OS.LINUX) {
                // MPS obtained via Gradle may miss +x; these calls return false if they don't work
                listOf("bin/mps.sh", "bin/linux/fsnotifier", "bin/linux/restart.py").forEach { File(spec.mpsPath, it).setExecutable(true) }
            }

            val platform = Platform.of(os, runCatching { Arch.current }.getOrDefault(Arch.AMD64))
            val mpsHome = mpsHome(spec.mpsPath, platform)
            val launcher = DistributionLayout.selectLauncher(mpsHome, platform)
            info("$os: MPS home $mpsHome, launcher ${launcher.path} (${launcher.kind})")

            val script = File(spec.environmentDir, MessageFormat.format(template.fileName, os.toString(), spec.environmentName))
            script.writeText(
                fillTemplate(
                    readTemplate(template.templatePath),
                    comments = mapOf("GENERATION_DATE" to date, "VERSION" to spec.generatorVersion),
                    values = mapOf(
                        "CONFIG_PATH" to spec.environmentDir.toString(),
                        "CONFIG_MPS_PATH" to spec.mpsConfigDir.toString(),
                        "MPS_PATH" to mpsHome.toString(),
                        "MPS_LAUNCHER" to launcher.path.let { if (os == OS.WINDOWS) it.replace('/', '\\') else it },
                        "JAVA_HOME" to (spec.javaHome?.toString() ?: ""),
                        "ENVIRONMENT_NAME" to spec.environmentName,
                        "MISSING_MPS_HINT" to spec.missingMpsHint,
                        "HTTP_PORT" to "${spec.httpPort}",
                        // debugging can also be enabled per start with -d, so the port is always set
                        "DEBUG_ENABLED" to "${spec.debug}",
                        "DEBUG_PORT" to "${spec.debugPort}",
                        "DEBUG_SUSPEND" to if (spec.debugSuspend) "y" else "n",
                    ),
                    escape = template.escape,
                )
            )
            script.setExecutable(true)
        }
    }

    private enum class ScriptTemplate(val fileName: String, val templatePath: String, val escape: (key: String, value: String) -> String) {
        LINUX(Constants.MPS_RUN_SCRIPT_LINUX_FILENAME, Constants.MPS_RUN_SCRIPT_LINUX_TEMPLATE_PATH, { _, v -> escapeShell(v) }),
        MAC(Constants.MPS_RUN_SCRIPT_MAC_FILENAME, Constants.MPS_RUN_SCRIPT_MAC_TEMPLATE_PATH, { _, v -> escapeShell(v) }),
        // the hint is the only value in an unquoted ECHO line; all others are inside quotes
        WINDOWS(Constants.MPS_RUN_SCRIPT_WIN_FILENAME, Constants.MPS_RUN_SCRIPT_WIN_TEMPLATE_PATH,
            { k, v -> if (k == "MISSING_MPS_HINT") escapeBatchEcho(v) else escapeBatchQuoted(v) }),
    }

    /** replaces `REPLACE_ME__<key>`; [values] are escaped for the script language, [comments] are not */
    private fun fillTemplate(
        template: String, comments: Map<String, String>, values: Map<String, String>, escape: (key: String, value: String) -> String,
    ): String =
        (comments + values.mapValues { escape(it.key, it.value) }).entries
            // longest key first, so no key replaces a part of another one
            .sortedByDescending { it.key.length }
            .fold(template) { text, (key, value) -> text.replace("REPLACE_ME__$key", value) }

    private fun readTemplate(path: String): String =
        requireNotNull(EnvironmentGenerator::class.java.getResource(path)) { "Missing template resource '$path'" }.readText()

    companion object {
        const val CREATED_FILE = "created.txt"

        private val START_SCRIPT = Regex("""startMPS_(LINUX|MAC|WINDOWS)_.+\.(sh|bat)""")

        /** script file name of an environment for [os], e.g. `startMPS_LINUX_Default.sh` */
        fun startScriptName(os: OS, environmentName: String): String = MessageFormat.format(
            when (os) {
                OS.WINDOWS -> Constants.MPS_RUN_SCRIPT_WIN_FILENAME
                OS.MAC -> Constants.MPS_RUN_SCRIPT_MAC_FILENAME
                else -> Constants.MPS_RUN_SCRIPT_LINUX_FILENAME
            },
            os.toString(), environmentName,
        )

        /** an explicit macOS `mpsPath` may point at the app bundle instead of its `Contents` */
        fun mpsHome(mpsPath: File, platform: Platform): File {
            val contents = File(mpsPath, "Contents")
            return if (platform.os == OS.MAC && File(contents, "bin").isDirectory) contents else mpsPath
        }
    }
}

/** for values inside double quotes in sh/bash */
internal fun escapeShell(value: String): String = value.replace(Regex("""[\\"$`]""")) { "\\" + it.value }

/** for values inside double quotes in batch files, e.g. `SET "X=..."` */
internal fun escapeBatchQuoted(value: String): String = value.replace("%", "%%")

/** for values in unquoted `ECHO` lines of batch files */
internal fun escapeBatchEcho(value: String): String = escapeBatchQuoted(value).replace(Regex("""[\^&|<>()]""")) { "^" + it.value }
