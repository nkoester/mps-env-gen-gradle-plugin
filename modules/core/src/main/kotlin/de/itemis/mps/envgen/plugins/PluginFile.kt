package de.itemis.mps.envgen.plugins

import java.io.File
import java.util.Properties

/**
 * plugin declarations in a properties file, e.g. `mps-plugins.properties`, shared by the CLI and the Gradle plugin:
 * ```
 * tool=../tools/my-tool-1.0.0-2025.1.zip
 * tool.property.some.key=value
 * tool.port.server.port=8600
 * tool.compatible=true
 * ```
 * Relative paths resolve against the folder of the file, `~/` against the home folder.
 */
object PluginFile {

    private val KEY = Regex("""([A-Za-z0-9_-]+)(?:\.(property|port)\.([A-Za-z0-9._-]+)|\.(compatible))?""")

    /** @throws PluginInstallationException naming the file and key of the first error */
    fun read(file: File): List<PluginDeclaration> {
        if (!file.isFile) throw PluginInstallationException("Plugin file $file not found")
        return parse(file.readText(), file.absoluteFile.parentFile, file.toString())
    }

    /**
     * declarations in [text], the contents of a plugin file in [baseDir]; [origin] names the file in errors
     *
     * @throws PluginInstallationException naming the file and key of the first error
     */
    fun parse(text: String, baseDir: File, origin: String): List<PluginDeclaration> {
        val keys = mutableListOf<String>()
        // keeps the order of the file, so plugins are listed as declared
        val props = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                keys += key.toString()
                return super.put(key, value)
            }
        }
        props.load(text.reader())
        return parse(keys.distinct().associateWith { props.getProperty(it) }, baseDir, origin)
    }

    internal fun parse(entries: Map<String, String>, baseDir: File, origin: String): List<PluginDeclaration> {
        fun fail(key: String, message: String): Nothing = throw PluginInstallationException("$origin, $key: $message")

        val sources = linkedMapOf<String, String>()
        val properties = mutableMapOf<String, MutableMap<String, String>>()
        val ports = mutableMapOf<String, MutableMap<String, Int>>()
        val compatible = mutableMapOf<String, Boolean>()

        entries.forEach { (key, rawValue) ->
            val value = rawValue.trim()
            val match = KEY.matchEntire(key)
                ?: fail(key, "unknown key; expected <name>, <name>.property.<key>, <name>.port.<key> or <name>.compatible")
            val name = match.groupValues[1]
            when {
                match.groupValues[2] == "property" -> properties.getOrPut(name) { linkedMapOf() }[match.groupValues[3]] = value
                match.groupValues[2] == "port" -> ports.getOrPut(name) { linkedMapOf() }[match.groupValues[3]] =
                    value.toIntOrNull()?.takeIf { it in 1..65535 } ?: fail(key, "'$value' is not a port")
                match.groupValues[4] == "compatible" -> compatible[name] = when (value.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> fail(key, "'$value' is neither true nor false")
                }
                value.isEmpty() -> fail(key, "no zip or folder given")
                else -> sources[name] = value
            }
        }
        (properties.keys + ports.keys + compatible.keys).firstOrNull { it !in sources }?.let {
            fail(it, "settings for plugin '$it', which is not declared; add $it=<zip or folder>")
        }
        return sources.map { (name, source) ->
            PluginDeclaration(
                name = name,
                source = source,
                path = resolve(source, baseDir),
                systemProperties = properties[name].orEmpty(),
                ports = ports[name].orEmpty(),
                compatible = compatible[name] ?: false,
            )
        }
    }

    /** `~/` is the home folder; relative paths resolve against [baseDir] */
    fun resolve(source: String, baseDir: File): File {
        val expanded = if (source == "~" || source.startsWith("~/") || source.startsWith("~\\")) {
            System.getProperty("user.home") + source.substring(1)
        } else {
            source
        }
        val file = File(expanded)
        return (if (file.isAbsolute) file else File(baseDir, expanded)).absoluteFile.normalize()
    }

    /** later declarations replace earlier ones of the same name; the position of the first one is kept */
    fun merge(vararg lists: List<PluginDeclaration>): List<PluginDeclaration> {
        val result = linkedMapOf<String, PluginDeclaration>()
        lists.forEach { list -> list.forEach { result[it.name] = it } }
        return result.values.toList()
    }
}
