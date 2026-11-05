package de.itemis.mps.envgen.plugins

import de.itemis.mps.envgen.environment.escapeBatchEcho
import de.itemis.mps.envgen.environment.escapeBatchQuoted
import de.itemis.mps.envgen.environment.escapeShell

/**
 * start script lines that list the managed plugins on every start and check them: missing folders, plugins disabled
 * in MPS, warnings from the installation, and busy ports (MPS is not started then, as clients use the fixed port).
 * Plugins found in the folder but not managed are listed by the templates.
 */
object PluginScripts {

    private const val DISABLED = "config/disabled_plugins.txt"

    /** POSIX shell lines, used by the Linux (bash) and macOS (sh) scripts; [restoreHint] tells how to regenerate */
    fun shell(plugins: List<InstalledPlugin>, restoreHint: String): String = buildString {
        fun quoted(text: String) = "\"${escapeShell(text)}\""
        fun print(text: String, error: Boolean = false) = "printf '%s\\n' ${quoted(text)}" + if (error) " >&2" else ""

        appendLine("MANAGED_PLUGIN_FOLDERS=${quoted(plugins.flatMap { it.folders }.joinToString(" ", " ", " "))}")
        if (plugins.isEmpty()) {
            appendLine(print("Plugins: none managed"))
            return@buildString
        }
        appendLine(print("Plugins (managed by mps-env-gen):"))
        summary(plugins).forEach { appendLine(print(it)) }
        plugins.forEach { plugin ->
            val name = plugin.declaration.name
            plugin.folders.forEachIndexed { i, folder ->
                appendLine("[ -d \"\${CONFIG_MPS_PATH}/plugins/${escapeShell(folder)}\" ] || " +
                    print("WARNING: plugin '$name': folder plugins/$folder is missing; restore: $restoreHint"))
                plugin.ids.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { id ->
                    appendLine("grep -qxF -- ${quoted(id)} \"\${CONFIG_MPS_PATH}/$DISABLED\" 2>/dev/null && " +
                        print("WARNING: plugin '$name' ($id) is disabled in $DISABLED"))
                }
            }
            plugin.warnings.forEach { appendLine(print("WARNING: $it")) }
            plugin.declaration.ports.forEach { (key, port) ->
                appendLine("if isPortInUse $port; then")
                appendLine("    " + print("ERROR: port $port of plugin '$name' ($key) is in use; MPS is not started.", error = true))
                appendLine("    " + print("Stop what uses it or change the port in the plugin declaration.", error = true))
                appendLine("    exit 1")
                appendLine("fi")
            }
        }
    }

    /** batch lines for the Windows script; outside of ( ) blocks, as texts may contain parentheses */
    fun batch(plugins: List<InstalledPlugin>, restoreHint: String): String = buildString {
        fun echo(text: String) = "ECHO ${escapeBatchEcho(text)}"

        appendLine("SET \"MANAGED_PLUGIN_FOLDERS=${escapeBatchQuoted(plugins.flatMap { it.folders }.joinToString(" ", " ", " "))}\"")
        if (plugins.isEmpty()) {
            appendLine(echo("Plugins: none managed"))
            return@buildString
        }
        appendLine(echo("Plugins (managed by mps-env-gen):"))
        summary(plugins).forEach { appendLine(echo(it)) }
        plugins.forEachIndexed { p, plugin ->
            val name = plugin.declaration.name
            plugin.folders.forEachIndexed { i, folder ->
                appendLine("IF NOT EXIST \"%CONFIG_MPS_PATH%\\plugins\\${escapeBatchQuoted(folder)}\\\" " +
                    echo("WARNING: plugin '$name': folder plugins\\$folder is missing; restore: $restoreHint"))
                plugin.ids.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { id ->
                    appendLine("findstr /X /C:\"${escapeBatchQuoted(id)}\" \"%CONFIG_MPS_PATH%\\${DISABLED.replace('/', '\\')}\" >NUL 2>&1 && " +
                        echo("WARNING: plugin '$name' ($id) is disabled in ${DISABLED.replace('/', '\\')}"))
                }
            }
            plugin.warnings.forEach { appendLine(echo("WARNING: $it")) }
            plugin.declaration.ports.entries.forEachIndexed { k, (key, port) ->
                val label = "pluginPortFree_${p}_$k"
                appendLine("netstat -an -p TCP | findstr /C:\":$port \" | findstr LISTENING >NUL || GOTO :$label")
                appendLine(echo("ERROR: port $port of plugin '$name' ($key) is in use; MPS is not started."))
                appendLine(echo("Stop what uses it or change the port in the plugin declaration."))
                appendLine("PAUSE")
                appendLine("EXIT /B 1")
                appendLine(":$label")
            }
        }
    }

    /** one line per plugin: name, source and what it contains, aligned */
    private fun summary(plugins: List<InstalledPlugin>): List<String> {
        val width = plugins.maxOf { it.declaration.name.length }
        return plugins.map { plugin ->
            val d = plugin.declaration
            val contents = plugin.ids.filter { it.isNotEmpty() }.ifEmpty { plugin.folders }.joinToString()
            val ports = d.ports.entries.joinToString("") { (key, port) -> ", $key=$port" }
            "  ${d.name.padEnd(width)}  ${d.source}  ($contents$ports)"
        }
    }
}
