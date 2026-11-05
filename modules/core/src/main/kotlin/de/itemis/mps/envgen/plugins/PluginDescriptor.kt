package de.itemis.mps.envgen.plugins

import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** the parts of a `META-INF/plugin.xml` relevant for installation */
data class PluginDescriptor(
    val id: String,
    val version: String?,
    val sinceBuild: String?,
    val untilBuild: String?,
    /** IDs of required plugins and modules; optional dependencies are left out */
    val depends: List<String>,
) {
    companion object {
        private const val PLUGIN_XML = "META-INF/plugin.xml"
        private const val ROOT_SEARCH_DEPTH = 3

        /**
         * descriptor of the plugin in [root]: `META-INF/plugin.xml` in the folder (MPS layout) or in one of its
         * jars in `lib/` (IntelliJ layout). Null if there is none.
         *
         * @throws PluginInstallationException if a descriptor exists but cannot be read
         */
        fun of(root: File): PluginDescriptor? {
            File(root, PLUGIN_XML).takeIf { it.isFile }?.let { file -> return file.inputStream().use { parse(it, file.path) } }
            return jars(root).firstNotNullOfOrNull { fromJar(it) }
        }

        /** null for jars without descriptor and for unreadable jars, which MPS would skip as well */
        private fun fromJar(jar: File): PluginDescriptor? = try {
            ZipFile(jar).use { zip -> zip.getEntry(PLUGIN_XML)?.let { entry -> zip.getInputStream(entry).use { parse(it, "$jar!/$PLUGIN_XML") } } }
        } catch (e: IOException) {
            null
        }

        /** plugin folders below [dir], [dir] itself included; does not descend into found plugins */
        fun findRoots(dir: File, depth: Int = ROOT_SEARCH_DEPTH): List<File> = when {
            isRoot(dir) -> listOf(dir)
            depth == 0 -> emptyList()
            else -> dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.flatMap { findRoots(it, depth - 1) }
        }

        /**
         * IDs of the plugins in the `plugins` folder of an MPS installation, from every descriptor of every plugin
         * folder; empty if there is none. Takes about a second for a full MPS.
         */
        fun bundledIds(mpsHome: File): Set<String> =
            listOf("plugins", "Contents/plugins").map { File(mpsHome, it) }.filter { it.isDirectory }
                .flatMap { dir -> dir.listFiles { f -> f.isDirectory }.orEmpty().toList() }
                .flatMap { plugin ->
                    val folder = File(plugin, PLUGIN_XML).takeIf { it.isFile }?.let { file -> runCatching { file.inputStream().use { parse(it, file.path) } }.getOrNull() }
                    listOfNotNull(folder) + jars(plugin).mapNotNull { runCatching { fromJar(it) }.getOrNull() }
                }
                .map { it.id }
                .toSet()

        private fun isRoot(dir: File): Boolean =
            File(dir, PLUGIN_XML).isFile || jars(dir).any { jar -> runCatching { ZipFile(jar).use { it.getEntry(PLUGIN_XML) != null } }.getOrDefault(false) }

        private fun jars(root: File): List<File> =
            File(root, "lib").listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }

        internal fun parse(input: InputStream, origin: String): PluginDescriptor {
            val document = try {
                DocumentBuilderFactory.newInstance().apply {
                    // plugin.xml comes from downloaded archives; IntelliJ's own declare a DOCTYPE with an external
                    // DTD, so DOCTYPEs are allowed, but nothing external is loaded and expansion is limited
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }.newDocumentBuilder().parse(input)
            } catch (e: Exception) {
                throw PluginInstallationException("Unable to read $origin: ${e.message}")
            }
            val root = document.documentElement
            fun children(name: String): List<Element> =
                (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }
            fun text(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            fun attribute(element: Element?, name: String): String? = element?.getAttribute(name)?.trim()?.takeIf { it.isNotEmpty() }

            // without <id>, IntelliJ uses the name
            val id = text("id") ?: text("name") ?: throw PluginInstallationException("$origin has neither <id> nor <name>")
            val ideaVersion = children("idea-version").firstOrNull()
            return PluginDescriptor(
                id = id,
                version = text("version"),
                sinceBuild = attribute(ideaVersion, "since-build"),
                untilBuild = attribute(ideaVersion, "until-build"),
                depends = children("depends").filter { it.getAttribute("optional") != "true" }
                    .mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) },
            )
        }
    }
}
