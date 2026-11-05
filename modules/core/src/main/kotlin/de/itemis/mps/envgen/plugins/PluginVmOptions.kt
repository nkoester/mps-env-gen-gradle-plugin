package de.itemis.mps.envgen.plugins

import java.io.File

/**
 * system properties of plugins in `mps64.vmoptions`. The file belongs to the user, but this block belongs to the
 * tool and is rewritten on every generation, so changed declarations always reach MPS.
 */
object PluginVmOptions {

    const val BEGIN = "#BEGIN mps-env-gen plugins (rewritten by mps-env-gen; change the plugin declarations instead)"
    const val END = "#END mps-env-gen plugins"

    /** @throws PluginInstallationException if two plugins set the same property */
    fun check(declarations: List<PluginDeclaration>) {
        declarations.flatMap { d -> (d.systemProperties.keys + d.ports.keys).map { it to d.name } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .entries.firstOrNull()?.let { (key, names) ->
                throw PluginInstallationException("System property $key is set by more than one plugin: ${names.joinToString()}")
            }
    }

    /** the block for [declarations]; empty if none sets a property */
    fun block(declarations: List<PluginDeclaration>): List<String> {
        val lines = declarations.filter { it.systemProperties.isNotEmpty() || it.ports.isNotEmpty() }.flatMap { d ->
            listOf("#${d.name}") +
                d.systemProperties.map { (key, value) -> "-D$key=$value" } +
                d.ports.map { (key, port) -> "-D$key=$port" }
        }
        return if (lines.isEmpty()) emptyList() else listOf(BEGIN) + lines + END
    }

    /** replaces the block in [file] (appended at the end if missing, removed if empty); keeps everything else */
    fun update(file: File, declarations: List<PluginDeclaration>) {
        if (!file.isFile) return
        val lines = file.readLines()
        val begin = lines.indexOf(BEGIN)
        val end = lines.indexOf(END).takeIf { it > begin }
        val outside = if (begin >= 0 && end != null) lines.subList(0, begin) + lines.subList(end + 1, lines.size) else lines
        val updated = outside.dropLastWhile { it.isBlank() } + block(declarations)
        if (updated != lines) file.writeText(updated.joinToString("\n", postfix = "\n"))
    }

    /** `-D<key>=` options of [vmOptions] that a plugin sets as well; the plugin's value wins, as it comes later */
    fun overridden(declarations: List<PluginDeclaration>, vmOptions: List<String>): List<String> {
        val keys = declarations.flatMap { it.systemProperties.keys + it.ports.keys }.toSet()
        return vmOptions.filter { option -> option.startsWith("-D") && option.removePrefix("-D").substringBefore('=') in keys }
    }
}
