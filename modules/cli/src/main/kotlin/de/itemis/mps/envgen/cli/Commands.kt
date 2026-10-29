package de.itemis.mps.envgen.cli

import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.CoreNoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.PlaintextHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.sources.PropertiesValueSource
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.MpsVersion
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.environment.EnvironmentGenerator
import de.itemis.mps.envgen.environment.EnvironmentMetadata
import de.itemis.mps.envgen.environment.EnvironmentPart
import de.itemis.mps.envgen.environment.EnvironmentSpec
import de.itemis.mps.envgen.environment.MpsOrigin
import de.itemis.mps.envgen.environment.Ports
import de.itemis.mps.envgen.environment.StoredEnvironment
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MpsEnvGenCommand(services: CliServices = CliServices()) : CoreNoOpCliktCommand(name = "mps-env-gen") {

    override fun help(context: Context) =
        "Creates and maintains isolated MPS environments. Each environment is self-contained and started via its own start script."

    override fun helpEpilog(context: Context) = """
        Examples:
          mps-env-gen create 2025.3 --temp --start                  throw-away environment, started right away
          mps-env-gen create 2025.3 --project ./mps-project         environment for a project in ./.mpsconfig/2025.3
          mps-env-gen create /opt/MPS-2025.3 --name review          use an installed MPS
          mps-env-gen copy 2025.3 try-2026.1 --mps 2026.1           same settings and plugins, other MPS version
          mps-env-gen refresh 2025.3 --start -d                     start again, with debugging this time
          mps-env-gen refresh 2025.3 --xmx 6g                       apply a setting to an existing environment
          mps-env-gen init-defaults                                 template for defaults of create, e.g. installs=/vol/mps

        MPS versions are taken from --installs (or ${'$'}MPS_ENV_GEN_INSTALLS) if present there, otherwise downloaded.
        Without the tool, start an environment with its startMPS_<OS>_<name> script (add -d for debugging).
    """.trimIndent()

    init {
        versionOption(CliVersion.value)
        subcommands(
            CreateCommand(services), CopyCommand(services), RenameCommand(services), RefreshCommand(services),
            InitDefaultsCommand(services),
            CompletionCommand(
                name = "completion",
                help = "Print a tab completion script, e.g. 'source <(mps-env-gen completion bash)' in ~/.bashrc",
            ),
        )
        context { helpFormatter = { PlaintextHelpFormatter(it, showDefaultValues = true) } }
    }
}

/** shared by all commands working on environments */
abstract class EnvironmentCommand(name: String, protected val services: CliServices) : CoreCliktCommand(name) {

    protected val target by option("--target", envvar = "MPS_ENV_GEN_TARGET",
        help = "folder of the environments; bare environment names are looked up here")
        .file(canBeFile = false).default(File(".mpsconfig"))
    protected val output by OutputOptions()

    protected val targetPath: File get() = target.absoluteFile.normalize()

    protected fun info(message: String) = echo(message)
    protected fun verbose(message: String) {
        if (output.verbose) echo(message)
    }
    protected fun warn(message: String) = echo("Warning: $message", err = true)

    protected fun generator() = EnvironmentGenerator(
        lifecycle = ::verbose, info = ::verbose, error = { echo(it, err = true) }, warn = ::warn,
        overwriteHint = "use 'mps-env-gen refresh <env> --reset-settings' or the setting's option on refresh",
    )

    /** `<env>`: path to an environment (or its start script); a bare name is looked up in --target */
    protected fun locate(env: String): File {
        val path = File(env).absoluteFile.normalize()
        val candidates = listOfNotNull(
            path.takeIf { it.isDirectory },
            path.parentFile?.takeIf { path.isFile },
            File(targetPath, env).takeIf { !env.contains('/') && !env.contains('\\') },
        )
        return candidates.firstOrNull(EnvironmentMetadata::isEnvironment)
            ?: throw UsageError("'$env' is not an environment (looked for ${candidates.joinToString(", ").ifEmpty { path.path }})")
    }

    protected fun read(environmentDir: File): StoredEnvironment =
        EnvironmentMetadata.read(environmentDir, placeholderSpec(environmentDir))
            ?: throw failure("$environmentDir has no ${EnvironmentMetadata.FILE_NAME}; it was generated by an older version. Recreate it with 'mps-env-gen create'.")

    protected fun checkNewName(parent: File, name: String): File {
        if (name.isBlank() || name.contains('/') || name.contains('\\')) throw UsageError("Invalid environment name '$name'")
        if (Platform.isReservedEnvironmentName(name)) throw UsageError("Environment name '$name' is reserved for prepared MPS distributions (mps-<version>-<OS>)")
        val dir = File(parent, name)
        if (dir.exists()) throw failure("$dir exists already; use 'mps-env-gen refresh $dir' or another name")
        return dir
    }

    /** hints the start scripts print; the scripts must work without this tool, the hints only help to repair */
    protected fun withHints(spec: EnvironmentSpec): EnvironmentSpec = spec.copy(
        missingMpsHint = when (spec.mpsOrigin) {
            MpsOrigin.NEXUS -> "Prepare it again via: mps-env-gen refresh ${spec.environmentDir}"
            else -> "Point the environment to another MPS via: mps-env-gen refresh ${spec.environmentDir} --mps <version|path>"
        },
        generatorVersion = CliVersion.value,
    )

    /** runs the start script for --start; the environment does not depend on this tool to start */
    protected fun startIfRequested(spec: EnvironmentSpec, args: List<String>?) {
        args ?: return
        val script = File(spec.environmentDir, EnvironmentGenerator.startScriptName(OS.current, spec.environmentName))
        info("Starting ${spec.environmentName} ...")
        val exitCode = services.runScript(script, args)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }

    /** always tells how to start the environment and copies the command to the clipboard */
    protected fun printStart(spec: EnvironmentSpec, verb: String) {
        val stored = EnvironmentMetadata.read(spec.environmentDir, spec)
        val script = File(spec.environmentDir, EnvironmentGenerator.startScriptName(OS.current, spec.environmentName))
        val debug = if (spec.debug) "debug port ${spec.debugPort}" else "debugging off"
        info("Environment '${spec.environmentName}' $verb in ${spec.environmentDir}")
        info("  created ${stored?.created ?: "?"}, MPS ${spec.mpsSource} (${spec.mpsOrigin.name.lowercase()}), " +
            "${spec.jbrVersion?.let { "JBR $it, " } ?: ""}$debug, http port ${spec.httpPort}")
        info("Start it with:")
        info("    $script")

        if (output.copy) {
            if (services.clipboard(script.path)) info("Copied to the clipboard.")
            else verbose("No clipboard tool found (wl-copy, xclip, xsel, pbcopy or clip.exe)")
        }
    }

    private fun placeholderSpec(environmentDir: File) = EnvironmentSpec(
        targetPath = environmentDir.parentFile, environmentName = environmentDir.name, os = listOf(OS.current),
        mpsPath = File(""), missingMpsHint = "", generatorVersion = CliVersion.value,
    )
}

class CreateCommand(services: CliServices) : EnvironmentCommand("create", services) {

    override fun help(context: Context) = "Create a new environment for <mps> (version, e.g. 2025.3, or path to an installation)."

    override fun helpEpilog(context: Context) = """
        Examples:
          mps-env-gen create 2025.3 --temp --start
          mps-env-gen create 2025.3 --installs /vol/mps --project ./mps-project --theme dark
          mps-env-gen create MPS-2022.2.4-linux --installs /vol/mps --name legacy
          mps-env-gen create /opt/MPS-2025.3 --xmx 6g --vm-option=-Dmy.option=1
    """.trimIndent()

    private val mps by argument("mps", help = "MPS version, folder name in --installs, or path to an MPS installation",
        completionCandidates = INSTALLS_COMPLETION)
    private val name by option("--name", help = "environment name (default: MPS version; with --temp plus date and time)")
    private val temp by option("--temp", help = "throw-away environment below \$TMPDIR/mps-env-gen").flag()
    private val source by MpsSourceOptions()
    private val settings by SettingsOptions()
    private val startOptions by StartOptions()

    init {
        // defaults only for create: copy/refresh start from the stored settings of an environment
        context {
            valueSource = PropertiesValueSource.from(services.defaultsFile(), getKey = DEFAULTS_KEY)
        }
    }

    override fun run() {
        // checked before anything is written
        val startArgs = startOptions.scriptArguments()
        val parent = if (temp) services.tempDir().absoluteFile else targetPath
        // checked before resolving, which may download MPS
        val baseName = name ?: File(mps).takeIf { it.exists() }?.absoluteFile?.normalize()?.name ?: mps
        val envName = if (temp && name == null) "$baseName-${LocalDateTime.now().format(TIMESTAMP)}" else baseName
        val dir = checkNewName(parent, envName)
        val resolved = MpsResolver(source, services, ::info, ::verbose, ::warn).resolve(mps, parent)

        val defaults = EnvironmentSpec(
            targetPath = parent, environmentName = envName, os = listOf(OS.current),
            mpsPath = resolved.home, javaHome = resolved.javaHome,
            mpsOrigin = resolved.origin, mpsSource = resolved.source, jbrVersion = resolved.jbrVersion,
            debugPort = Ports.DEBUG_BASE, missingMpsHint = "", generatorVersion = CliVersion.value,
        )
        val (spec, _) = settings.applyTo(defaults, Ports.usedBySiblings(parent), reallocatePorts = true)
        generator().generate(withHints(spec), history = "created")
        printStart(withHints(spec).also { check(it.environmentDir == dir) }, "created")
        startIfRequested(spec, startArgs)
    }

    companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

class CopyCommand(services: CliServices) : EnvironmentCommand("copy", services) {

    override fun help(context: Context) =
        "Copy an environment (settings, plugins; not caches) to <new-name>, next to it or --into another folder."

    override fun helpEpilog(context: Context) = """
        Examples:
          mps-env-gen copy 2025.3 experiment                       next to the source, new ports
          mps-env-gen copy 2025.3 try-2026.1 --mps 2026.1 --start  same settings, other MPS version
          mps-env-gen copy .mpsconfig/main scratch --into /tmp --with-caches        into another folder, with caches
    """.trimIndent()

    private val env by argument("env", help = "environment path or name")
    private val newName by argument("new-name")
    private val into by option("--into", help = "folder for the copy (default: next to the source)").file(canBeFile = false)
    private val mps by option("--mps", help = "use another MPS (version, folder name in --installs, or path) for the copy",
        completionCandidates = INSTALLS_COMPLETION)
    private val withCaches by option("--with-caches", help = "also copy caches and logs").flag()
    private val source by MpsSourceOptions()
    private val settings by SettingsOptions()
    private val startOptions by StartOptions()

    override fun run() {
        // checked before anything is written
        val startArgs = startOptions.scriptArguments()
        val from = locate(env)
        val stored = read(from)
        val parent = (into ?: from.parentFile).absoluteFile.normalize()
        val dir = checkNewName(parent, newName)

        var spec = stored.spec.copy(targetPath = parent, environmentName = newName)
        mps?.let { spec = spec.withMps(MpsResolver(source, services, ::info, ::verbose, ::warn).resolve(it, parent)) }

        val folders = listOf("mps/config", "mps/plugins") + if (withCaches) listOf("mps/system", "mps/log", "mps/scratch") else emptyList()
        // user owned files are taken over as they are; idea.properties then gets the paths of the copy
        listOf("mps/mps64.vmoptions", "mps/idea.properties").map { File(from, it) }.filter { it.isFile }.forEach {
            it.copyTo(File(dir, it.relativeTo(from).path), overwrite = true)
        }
        folders.map { File(from, it) }.filter { it.isDirectory }.forEach { folder ->
            folder.walkTopDown().filter { it.isFile && it.name !in LOCK_FILES }.forEach {
                it.copyTo(File(dir, it.relativeTo(from).path), overwrite = true)
            }
        }

        // the source keeps its ports, so the copy gets new ones unless given
        val (applied, parts) = settings.applyTo(spec, Ports.usedBySiblings(parent), reallocatePorts = true)
        generator().generate(withHints(applied), overwrite = parts, history = "copied from $from")
        printStart(withHints(applied), "copied")
        startIfRequested(applied, startArgs)
    }
}

class RenameCommand(services: CliServices) : EnvironmentCommand("rename", services) {

    override fun help(context: Context) = "Rename an environment in place; caches and settings are kept."

    override fun helpEpilog(context: Context) = """
        Examples:
          mps-env-gen rename 2025.3 main
          mps-env-gen rename /tmp/mps-env-gen/2025.3-20261029-104450 keep-me
    """.trimIndent()

    private val env by argument("env", help = "environment path or name")
    private val newName by argument("new-name")
    private val force by option("--force", help = "rename even if MPS seems to be running (stale lock files)").flag()

    override fun run() {
        val from = locate(env)
        val stored = read(from)
        val locks = listOf("mps/config", "mps/system").flatMap { dir -> LOCK_FILES.map { File(from, "$dir/$it") } }.filter(::isHeld)
        if (locks.isNotEmpty() && !force) {
            throw failure("MPS is running from $from (${locks.joinToString()} locked); close it or use --force")
        }
        val dir = checkNewName(from.parentFile, newName)
        Files.move(from.toPath(), dir.toPath())

        val spec = withHints(stored.spec.copy(environmentName = newName))
        generator().generate(spec, history = "renamed from ${from.name}")
        printStart(spec, "renamed")
    }
}

class RefreshCommand(services: CliServices) : EnvironmentCommand("refresh", services) {

    override fun help(context: Context) =
        "Rewrite the start scripts and generated files of an environment, e.g. after moving it or to change settings."

    override fun helpEpilog(context: Context) = """
        Settings files that MPS or you may have changed are kept unless --reset-settings or the setting's option is given.

        Examples:
          mps-env-gen refresh main                                 after moving the environment
          mps-env-gen refresh main --start=tmuxLA -d               start in tmux with log window and debugging
          mps-env-gen refresh main --xmx 6g --theme dark           apply settings (previous files in .backup/)
          mps-env-gen refresh main --mps 2026.1                    switch the MPS version
          mps-env-gen refresh main --reset-settings                back to the generated defaults
    """.trimIndent()

    private val env by argument("env", help = "environment path or name")
    private val mps by option("--mps", help = "switch to another MPS (version, folder name in --installs, or path)",
        completionCandidates = INSTALLS_COMPLETION)
    private val resetSettings by option("--reset-settings",
        help = "rewrite vmoptions, idea.properties and the MPS option files with the stored or given settings " +
            "(otherwise they are kept, as they may contain changes made in MPS; backup in .backup/)").flag()
    private val source by MpsSourceOptions()
    private val settings by SettingsOptions()
    private val startOptions by StartOptions()

    override fun run() {
        // checked before anything is written
        val startArgs = startOptions.scriptArguments()
        val dir = locate(env)
        val stored = read(dir)
        // the location wins over what is stored: the environment may have been moved
        var spec = stored.spec.copy(targetPath = dir.parentFile, environmentName = dir.name)
        val resolver = MpsResolver(source, services, ::info, ::verbose, ::warn)

        spec = when {
            mps != null -> spec.withMps(resolver.resolve(mps!!, spec.targetPath))
            spec.mpsOrigin == MpsOrigin.NEXUS -> {
                val prepared = resolver.prepared(MpsVersion.parse(spec.mpsSource), spec.targetPath)
                // a JBR of the distribution is replaced, an explicitly chosen one kept
                val javaHome = spec.javaHome?.takeUnless { it.absoluteFile.startsWith(prepared.root.absoluteFile) } ?: prepared.javaHome
                spec.copy(mpsPath = prepared.home, javaHome = javaHome, jbrVersion = prepared.jbrVersion.toString())
            }
            else -> spec.also { if (!it.mpsPath.isDirectory) warn("MPS ${it.mpsPath} does not exist; use --mps to point to another one") }
        }

        val (applied, parts) = settings.applyTo(spec, Ports.usedBySiblings(spec.targetPath, exclude = dir), reallocatePorts = false)
        val overwrite = if (resetSettings) EnvironmentPart.USER_OWNED else parts
        generator().generate(withHints(applied), overwrite = overwrite, history = "refreshed")
        printStart(withHints(applied), "refreshed")
        startIfRequested(applied, startArgs)
    }
}

private fun EnvironmentSpec.withMps(resolved: ResolvedMps) = copy(
    mpsPath = resolved.home, javaHome = resolved.javaHome ?: javaHome.takeIf { resolved.origin != MpsOrigin.NEXUS },
    mpsOrigin = resolved.origin, mpsSource = resolved.source, jbrVersion = resolved.jbrVersion,
)

/** lock files of IntelliJ in the config and system folders (`port.lock` since 2020.x, `.lock` before); they stay after exit */
private val LOCK_FILES = setOf("port.lock", ".lock")

/** a running MPS holds a file lock on its lock files; their mere existence says nothing */
internal fun isHeld(file: File): Boolean {
    if (!file.isFile) return false
    return try {
        RandomAccessFile(file, "rw").use { raf -> raf.channel.tryLock()?.let { it.release(); false } ?: true }
    } catch (e: OverlappingFileLockException) {
        true
    } catch (e: IOException) {
        false
    }
}

/**
 * tab completion of `<mps>`: folder names in the installations folder given by --installs on the command line,
 * else by the environment; paths (words with '/') complete as folders. Globs instead of `ls`, which may be aliased.
 */
private val INSTALLS_COMPLETION = CompletionCandidates.Custom { shell ->
    when (shell) {
        CompletionCandidates.Custom.ShellType.BASH -> """
            local cur="${'$'}{COMP_WORDS[COMP_CWORD]}" dir="${'$'}{MPS_ENV_GEN_INSTALLS:-${'$'}{MPS_BASE_PATH:-}}" entry i
            local -a names=()
            if [[ "${'$'}{cur}" == */* ]]; then
                COMPREPLY=(${'$'}(compgen -d -- "${'$'}{cur}"))
                return
            fi
            # bash splits --installs=X into three words
            for (( i = 1; i < ${'$'}{#COMP_WORDS[@]}; i++ )); do
                if [[ "${'$'}{COMP_WORDS[i]}" == "--installs" ]]; then
                    dir="${'$'}{COMP_WORDS[i+1]}"
                    [[ "${'$'}{dir}" == "=" ]] && dir="${'$'}{COMP_WORDS[i+2]}"
                fi
            done
            dir="${'$'}{dir/#\~/${'$'}HOME}"
            if [[ -n "${'$'}{dir}" ]]; then
                for entry in "${'$'}{dir}"/*/; do
                    # MPS installations only, as accepted by create
                    [[ -d "${'$'}{entry}bin" || -d "${'$'}{entry}Contents/bin" ]] || continue
                    entry="${'$'}{entry%/}"
                    names+=("${'$'}{entry##*/}")
                done
            fi
            COMPREPLY=(${'$'}(compgen -W "${'$'}{names[*]}" -- "${'$'}{cur}"))
        """.trimIndent()
        // fish: from the environment only
        CompletionCandidates.Custom.ShellType.FISH ->
            "\"(for d in \$MPS_ENV_GEN_INSTALLS/*/bin/ \$MPS_BASE_PATH/*/bin/; basename (dirname \$d); end 2>/dev/null)\""
    }
}

/** a problem with the state on disk, not with the arguments: reported without usage help */
internal fun failure(message: String) = CliktError(message, statusCode = 1)
