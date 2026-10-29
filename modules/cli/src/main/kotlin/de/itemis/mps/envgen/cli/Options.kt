package de.itemis.mps.envgen.cli

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
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.core.UsageError
import de.itemis.mps.envgen.CACHE_ENV
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.userCacheDir
import de.itemis.mps.envgen.environment.EnvironmentPart
import de.itemis.mps.envgen.environment.EnvironmentSpec
import de.itemis.mps.envgen.environment.Ports
import de.itemis.mps.envgen.environment.Theme
import java.io.File

/** where MPS comes from if `<mps>` is a version */
class MpsSourceOptions : OptionGroup("MPS source") {
    val installs by option("--installs", envvar = "MPS_ENV_GEN_INSTALLS",
        help = "local MPS installations (MPS-<version>*), searched before downloading; fallback: \$MPS_BASE_PATH")
        .file(canBeFile = false)
    val noDownload by option("--no-download", help = "never download MPS").flag()
    val repository by option("--repository", envvar = "MPS_ENV_GEN_REPOSITORY", help = "Maven repository with MPS and JBR")
        .default(NexusArtifactSource.DEFAULT_URL)
    val cacheDir by option("--cache-dir", envvar = CACHE_ENV,
        help = "downloads and prepared MPS, shared by all environments; can be cleaned by hand")
        .file(canBeFile = false).default(userCacheDir())
    val mpsInTarget by option("--mps-in-target",
        help = "prepare MPS from Nexus in the target folder (mps-<version>-<OS>) instead of the shared cache folder").flag()
    val jbrVersion by option("--jbr-version", help = "JBR for a downloaded MPS (default: derived from the MPS version)")
}

/**
 * Settings of an environment. All nullable: `create` fills in defaults, `copy`/`refresh` only change what is given.
 */
class SettingsOptions : OptionGroup("Environment settings") {
    val project by option("--project", help = "MPS project to trust and open").file(mustExist = true, canBeFile = false)
    val javaHome by option("--java-home", help = "JBR/JDK to run MPS with (default: the JBR of a downloaded MPS)")
        .file(mustExist = true, canBeFile = false)
    val debug by option("--debug", metavar = "PORT", help = "always enable remote debugging, optionally on PORT (default: off; the start script enables it per start with -d)")
        .int().optionalValue(AUTO_PORT)
    val noDebug by option("--no-debug", help = "disable remote debugging").flag()
    // nullable flags instead of switches: switches cannot be read from the defaults file
    val debugSuspend by option("--debug-suspend", help = "suspend MPS until a debugger is attached")
        .nullableFlag("--no-debug-suspend")
    val httpPort by option("--http-port", metavar = "PORT|auto",
        help = "fixed port for opening nodes from URLs, MPS does not start if it is in use; auto: the start script takes " +
            "the first free one from ${Ports.HTTP_BASE} (default)")
        .convert { value ->
            if (value == "auto") AUTO_PORT
            else value.toIntOrNull()?.takeIf { it in 1..65535 } ?: fail("'$value' is neither a port nor 'auto'")
        }
    val theme by option("--theme", help = "UI theme").choice(mapOf("light" to Theme.LIGHT, "dark" to Theme.DARK))
    val xms by option("--xms", help = "initial heap, e.g. 1024m")
    val xmx by option("--xmx", help = "maximum heap, e.g. 4096m")
    val vmOptions by option("--vm-option", help = "additional line for the vmoptions file; repeatable, replaces stored ones").multiple()
    val ideaProperties by option("--idea-property", help = "additional line for idea.properties; repeatable, replaces stored ones").multiple()
    val modelCheck by option("--model-check-before-generation", help = "check models before generation (disabled by default)")
        .nullableFlag("--no-model-check-before-generation")

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

/** starts the generated environment through its own start script */
class StartOptions : OptionGroup("Start") {
    val start by option("--start", metavar = "MODE",
        help = "start the environment afterwards via its start script; MODE (Linux): tmuxD, tmuxA, tmuxLD, tmuxLA")
        .optionalValue("")
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
    val copy by option("--copy", help = "copy the start command to the clipboard if a clipboard tool is available")
        .flag("--no-copy", default = true, defaultForHelp = "true")
    val verbose by option("--verbose", help = "more output").flag()
}
