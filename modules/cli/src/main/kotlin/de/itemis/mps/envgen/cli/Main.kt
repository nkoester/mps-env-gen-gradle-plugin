package de.itemis.mps.envgen.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val command = MpsEnvGenCommand()
    // clikt-core's main() does not set the exit code, so errors are mapped here
    try {
        command.parse(args)
    } catch (e: CliktError) {
        command.echoFormattedHelp(e)
        exitProcess(e.statusCode)
    }
}

/** version of this tool; generated at build time, as jar manifests are not available in a native image */
internal object CliVersion {
    val value: String by lazy {
        CliVersion::class.java.getResource("/mps-env-gen-version.txt")?.readText()?.trim() ?: "unknown"
    }
}
