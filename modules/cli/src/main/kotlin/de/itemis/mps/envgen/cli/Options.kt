package de.itemis.mps.envgen.cli

import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.core.UsageError
import de.itemis.mps.envgen.CACHE_ENV
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.userCacheDir
import de.itemis.mps.envgen.environment.EnvironmentPart
import de.itemis.mps.envgen.environment.EnvironmentSpec
import de.itemis.mps.envgen.environment.Ports
import de.itemis.mps.envgen.environment.Theme
import de.itemis.mps.envgen.plugins.PluginDeclaration
import de.itemis.mps.envgen.plugins.PluginFile
import de.itemis.mps.envgen.plugins.PluginInstallationException
import java.io.File

/** where MPS comes from if `<mps>` is a version */
class MpsSourceOptions : OptionGroup("MPS source") {
    val installs by option("-i", "--installs", envvar = "MPS_ENV_GEN_INSTALLS",
        help = "local MPS installations (MPS-<version>*), searched before downloading; fallback: \$MPS_BASE_PATH")
        .file(canBeFile = false)
    val noDownload by option("-o", "--no-download", help = "never download MPS").flag()
    val repository by option("-r", "--repository", envvar = "MPS_ENV_GEN_REPOSITORY", help = "Maven repository with MPS and JBR")
        .default(NexusArtifactSource.DEFAULT_URL)
    val cacheDir by option("-c", "--cache-dir", envvar = CACHE_ENV,
        help = "downloads and prepared MPS, shared by all environments; can be cleaned by hand")
        .file(canBeFile = false).default(userCacheDir())
    val mpsInTarget by option("-I", "--mps-in-target",
        help = "prepare MPS from Nexus in the target folder (mps-<version>-<OS>) instead of the shared cache folder").flag()
    val jbrVersion by option("-j", "--jbr-version", help = "JBR for a downloaded MPS (default: derived from the MPS version)")
}

/**
 * Settings of an environment. All nullable: `create` fills in defaults, `copy`/`refresh` only change what is given.
 */
class SettingsOptions : OptionGroup("Environment settings") {
    val project by option("-p", "--project", help = "MPS project to trust and open").file(mustExist = true, canBeFile = false)
    val javaHome by option("-J", "--java-home", help = "JBR/JDK to run MPS with (default: the JBR of a downloaded MPS)")
        .file(mustExist = true, canBeFile = false)
    // optional values only attached (--debug=PORT, -x5005, -x=5005), so options and arguments can come in any order
    val debug by option("-x", "--debug", metavar = "PORT", help = "always enable remote debugging, optionally on PORT (default: off; the start script enables it per start with -d)")
        .convert { value -> value.removePrefix("=").toIntOrNull() ?: fail("'$value' is not a port") }
        .optionalValue(AUTO_PORT, acceptsUnattachedValue = false)
    val noDebug by option("--no-debug", help = "disable remote debugging").flag()
    // nullable flags instead of switches: switches cannot be read from the defaults file
    val debugSuspend by option("-S", "--debug-suspend", help = "suspend MPS until a debugger is attached")
        .nullableFlag("--no-debug-suspend")
    val httpPort by option("-P", "--http-port", metavar = "PORT|auto",
        help = "fixed port for opening nodes from URLs, MPS does not start if it is in use; auto: the start script takes " +
            "the first free one from ${Ports.HTTP_BASE} (default)")
        .convert { value ->
            if (value == "auto") AUTO_PORT
            else value.toIntOrNull()?.takeIf { it in 1..65535 } ?: fail("'$value' is neither a port nor 'auto'")
        }
    val theme by option("-e", "--theme", help = "UI theme").choice(mapOf("light" to Theme.LIGHT, "dark" to Theme.DARK))
    val xms by option("-L", "--xms", help = "initial heap, e.g. 1024m")
    val xmx by option("-M", "--xmx", help = "maximum heap, e.g. 4096m")
    val vmOptions by option("-O", "--vm-option", help = "additional line for the vmoptions file; repeatable, replaces stored ones").multiple()
    val ideaProperties by option("-D", "--idea-property", help = "additional line for idea.properties; repeatable, replaces stored ones").multiple()
    val modelCheck by option("-g", "--model-check-before-generation", help = "check models before generation (disabled by default)")
        .nullableFlag("--no-model-check-before-generation")
    // the help default makes init-defaults offer the value that changes it: update-checks=false
    val updateChecks by option("-u", "--update-checks", help = "MPS checks for updates of itself and its plugins",
        helpTags = mapOf(HelpFormatter.Tags.DEFAULT to "true"))
        .nullableFlag("--no-update-checks")
    val mergeDriverNotification by option("-N", "--merge-driver-notification",
        help = "MPS shows the VCS Addons popup about the merge driver (disabled by default, the message is still logged)")
        .nullableFlag("--no-merge-driver-notification")

    /**
     * applies the given settings to [spec]; ports marked [AUTO_PORT] are allocated against [usedPorts].
     * Returns the user owned parts these options explicitly change, which are then rewritten.
     */
    fun applyTo(spec: EnvironmentSpec, usedPorts: Ports.Used, reallocatePorts: Boolean): Pair<EnvironmentSpec, Set<EnvironmentPart>> {
        val parts = mutableSetOf<EnvironmentPart>()
        var result = spec

        project?.let { result = result.copy(projectPath = it.absoluteFile.normalize()); parts += EnvironmentPart.PROJECT }
        javaHome?.let { result = result.copy(javaHome = it.absoluteFile.normalize()) }
        theme?.let { result = result.copy(theme = it); parts += EnvironmentPart.THEME }
        modelCheck?.let { result = result.copy(modelCheckBeforeGeneration = it); parts += EnvironmentPart.MODEL_CHECK }
        updateChecks?.let { result = result.copy(updateChecks = it); parts += EnvironmentPart.UPDATES }
        mergeDriverNotification?.let { result = result.copy(mergeDriverNotification = it); parts += EnvironmentPart.NOTIFICATIONS }
        xms?.let { result = result.copy(xms = it); parts += EnvironmentPart.VM_OPTIONS }
        xmx?.let { result = result.copy(xmx = it); parts += EnvironmentPart.VM_OPTIONS }
        debugSuspend?.let { result = result.copy(debugSuspend = it) }
        if (vmOptions.isNotEmpty()) { result = result.copy(vmOptions = vmOptions); parts += EnvironmentPart.VM_OPTIONS }
        if (ideaProperties.isNotEmpty()) { result = result.copy(ideaProperties = ideaProperties); parts += EnvironmentPart.IDEA_PROPERTIES }

        httpPort?.let { result = result.copy(httpPort = it.takeIf { port -> port != AUTO_PORT }) }

        result = when {
            noDebug -> result.copy(debug = false)
            debug != null && debug != AUTO_PORT -> result.copy(debug = true, debugPort = debug!!)
            debug == AUTO_PORT && !result.debug || reallocatePorts && (debug != null || result.debug) ->
                result.copy(debug = true, debugPort = Ports.allocate(Ports.DEBUG_BASE, usedPorts.debug))
            else -> result
        }
        return result to parts
    }

    companion object {
        /** `--debug` without port, `--http-port auto` */
        const val AUTO_PORT = -1
    }
}

/** plugins to install into the environment; see [applyTo] for the order */
class PluginOptions : OptionGroup("Plugins") {
    val pluginsFiles by option("--plugins-file", metavar = "FILE",
        help = "plugin declarations: <name>=<zip or folder>, <name>.property.<key>=<value>, <name>.port.<key>=<port>, " +
            "<name>.compatible=true; repeatable")
        .file(canBeDir = false).multiple()
    val plugins by option("--plugin", metavar = "[NAME=]PATH", help = "plugin zip or folder; NAME defaults to the file name; repeatable")
        .multiple()
    val properties by option("--plugin-property", metavar = "NAME:KEY=VALUE", help = "system property for a plugin; repeatable")
        .multiple()
    val ports by option("--plugin-port", metavar = "NAME:KEY=PORT",
        help = "system property holding a fixed port; MPS does not start if it is in use; repeatable")
        .multiple()
    val compatible by option("--plugin-compatible", metavar = "NAME",
        help = "install the plugin without asking if its compatibility with the MPS build is uncertain; repeatable")
        .multiple()
    val reinstall by option("--reinstall-plugins", help = "install all plugins again, also over folders not installed by mps-env-gen")
        .flag()

    /**
     * [stored] (empty on create, minus [removed], all with [clear]), then the plugin files in order, then --plugin;
     * a later declaration replaces an earlier one of the same name. Properties, ports and compatibility apply last.
     */
    fun applyTo(stored: List<PluginDeclaration>, removed: List<String> = emptyList(), clear: Boolean = false): List<PluginDeclaration> {
        val unknown = removed.filter { name -> stored.none { it.name == name } }
        if (unknown.isNotEmpty()) throw UsageError("No plugin ${unknown.joinToString()} in this environment")
        val base = if (clear) emptyList() else stored.filter { it.name !in removed }
        val files = try {
            pluginsFiles.map { PluginFile.read(it) }
        } catch (e: PluginInstallationException) {
            throw UsageError(e.message ?: "Invalid plugin file")
        }
        var result = PluginFile.merge(base, *files.toTypedArray(), plugins.map(::declaration))

        fun named(option: String, value: String): Pair<String, String> {
            val name = value.substringBefore(':', "")
            if (name.isEmpty() || result.none { it.name == name }) {
                throw UsageError("$option $value: no plugin '$name'; declared: ${result.joinToString { it.name }.ifEmpty { "none" }}")
            }
            return name to value.substringAfter(':')
        }
        fun update(name: String, change: (PluginDeclaration) -> PluginDeclaration) {
            result = result.map { if (it.name == name) change(it) else it }
        }
        properties.forEach { value ->
            val (name, setting) = named("--plugin-property", value)
            if ('=' !in setting) throw UsageError("--plugin-property $value: expected NAME:KEY=VALUE")
            update(name) { it.copy(systemProperties = it.systemProperties + (setting.substringBefore('=') to setting.substringAfter('='))) }
        }
        ports.forEach { value ->
            val (name, setting) = named("--plugin-port", value)
            val port = setting.substringAfter('=', "").toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw UsageError("--plugin-port $value: expected NAME:KEY=PORT")
            update(name) { it.copy(ports = it.ports + (setting.substringBefore('=') to port)) }
        }
        compatible.forEach { name ->
            named("--plugin-compatible", "$name:")
            update(name) { it.copy(compatible = true) }
        }
        return result
    }

    private fun declaration(value: String): PluginDeclaration {
        val prefix = value.substringBefore('=', "")
        val explicit = prefix.isNotEmpty() && PluginDeclaration.NAME.matches(prefix)
        val source = if (explicit) value.substringAfter('=') else value
        if (source.isEmpty()) throw UsageError("--plugin $value: no zip or folder given")
        val path = PluginFile.resolve(source, File("").absoluteFile)
        val name = if (explicit) prefix else defaultName(path)
        return PluginDeclaration(name, path.path, path)
    }

    companion object {
        /** file name without `.zip`, other characters than letters, digits, '_' and '-' replaced */
        fun defaultName(path: File): String =
            path.name.removeSuffix(".zip").replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "plugin" }
    }
}

/** starts the generated environment through its own start script */
class StartOptions : OptionGroup("Start") {
    val start by option("-s", "--start", metavar = "MODE",
        help = "start the environment afterwards via its start script; MODE (Linux): tmuxD, tmuxA, tmuxLD, tmuxLA")
        // Clikt keeps the '=' of -s=MODE
        .convert { it.removePrefix("=") }.optionalValue("", acceptsUnattachedValue = false)
    val debug by option("-d", "--start-debug", help = "with --start: enable debugging for this start (the script's -d)").flag()

    /** arguments for the start script, null without --start */
    fun scriptArguments(): List<String>? {
        val mode = start ?: return if (debug) throw UsageError("-d/--start-debug needs --start") else null
        if (mode.isNotEmpty() && mode !in TMUX_MODES) throw UsageError("Unknown start mode '$mode'; use one of ${TMUX_MODES.joinToString()}")
        if (mode.isNotEmpty() && OS.current != OS.LINUX) throw UsageError("Start mode '$mode' (tmux) is only available on Linux")
        return listOfNotNull("-d".takeIf { debug }, mode.ifEmpty { null })
    }

    companion object {
        val TMUX_MODES = listOf("tmuxD", "tmuxA", "tmuxLD", "tmuxLA")
    }
}

class OutputOptions : OptionGroup("Output") {
    val copy by option("-C", "--copy", help = "copy the start command to the clipboard if a clipboard tool is available")
        .flag("--no-copy", default = true, defaultForHelp = "true")
    val verbose by option("-v", "--verbose", help = "more output").flag()
}
