package de.itemis.mps.envgen.cli

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.ArtifactSource
import java.io.File
import java.util.concurrent.TimeUnit

/** side effects of the CLI, replaceable in tests */
class CliServices(
    val artifactSource: (repositoryUrl: String, cacheDir: File, log: (String) -> Unit) -> ArtifactSource =
        { url, cache, log -> NexusArtifactSource(url, cache, log, progress = { System.err.println(it) }) },
    val clipboard: (String) -> Boolean = SystemClipboard::copy,
    /** runs a start script in this terminal and returns its exit code */
    val runScript: (script: File, args: List<String>) -> Int = ::runStartScript,
    val getenv: (String) -> String? = System::getenv,
    /** parent of throw-away environments */
    val tempDir: () -> File = { File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "mps-env-gen") },
    /** defaults for `create` options, e.g. `xmx=4096m` */
    val defaultsFile: () -> File = {
        System.getenv("MPS_ENV_GEN_CONFIG")?.let(::File)
            ?: File(System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "mps-env-gen/defaults.properties")
    },
)

private fun runStartScript(script: File, args: List<String>): Int {
    val command = if (OS.current == OS.WINDOWS) listOf("cmd", "/c", script.path) else listOf(script.path)
    return ProcessBuilder(command + args).inheritIO().start().waitFor()
}

/** copies text to the system clipboard via the usual command line tools */
object SystemClipboard {

    fun copy(text: String): Boolean = candidates().any { command -> run(command, text) }

    private fun candidates(): List<List<String>> = when (OS.current) {
        OS.MAC -> listOf(listOf("pbcopy"))
        OS.WINDOWS -> listOf(listOf("clip.exe"))
        else -> listOfNotNull(
            listOf("wl-copy").takeIf { System.getenv("WAYLAND_DISPLAY") != null },
            listOf("xclip", "-selection", "clipboard").takeIf { System.getenv("DISPLAY") != null },
            listOf("xsel", "--clipboard", "--input").takeIf { System.getenv("DISPLAY") != null },
        )
    }

    /**
     * wl-copy and xclip fork a process that keeps serving the clipboard and inherits the output pipes,
     * so the output is discarded instead of read. A tool still running after the timeout is that server:
     * it counts as success and must not be killed, which would clear the clipboard.
     */
    private fun run(command: List<String>, text: String): Boolean = try {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.use { it.write(text.toByteArray()) }
        !process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() == 0
    } catch (e: Exception) {
        false
    }
}
