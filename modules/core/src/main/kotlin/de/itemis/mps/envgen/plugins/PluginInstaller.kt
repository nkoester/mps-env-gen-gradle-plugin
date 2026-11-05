package de.itemis.mps.envgen.plugins

import de.itemis.mps.envgen.distribution.Archives
import de.itemis.mps.envgen.environment.MpsBuild
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * installs declared plugins into the plugins folder of an environment. Two steps, so that nothing is written if a
 * declaration fails: [prepare] checks everything and extracts archives next to the plugins folder, [Plan.apply]
 * replaces the folders.
 *
 * Folders of plugins installed otherwise (e.g. from the IDE) are never touched.
 */
class PluginInstaller(
    private val pluginsDir: File,
    private val mpsHome: File,
    private val build: MpsBuild?,
    /** project of the environment; its `.mps/libraries.xml` may overlap with plugins containing languages */
    private val projectPath: File? = null,
    /** asked for plugins of uncertain compatibility; true installs them anyway */
    private val acceptUncertain: (message: String) -> Boolean = { false },
    private val warn: (String) -> Unit = {},
    private val info: (String) -> Unit = {},
) {

    /** one root of a declaration and the folder name it gets in [pluginsDir] */
    internal class Root(val dir: File, val folder: String, val descriptor: PluginDescriptor?)

    internal class Entry(
        val declaration: PluginDeclaration,
        val fingerprint: String,
        val roots: List<Root>,
        /** already installed as declared; nothing to copy */
        val unchanged: Boolean,
        val confirmedFor: String?,
        val warnings: MutableList<String>,
    )

    inner class Plan internal constructor(
        private val entries: List<Entry>,
        private val removed: List<InstalledPlugin>,
        private val previous: Map<String, InstalledPlugin>,
        private val staging: File,
    ) {
        /** the state after [apply], to store and to show in the start scripts */
        val installed: List<InstalledPlugin> = entries.map { entry ->
            InstalledPlugin(
                declaration = entry.declaration,
                fingerprint = entry.fingerprint,
                folders = entry.roots.map { it.folder },
                ids = entry.roots.map { it.descriptor?.id ?: "" },
                confirmedFor = entry.confirmedFor,
                warnings = entry.warnings.distinct(),
            )
        }

        /** replaces changed and removed plugins; returns [installed] */
        fun apply(): List<InstalledPlugin> {
            try {
                removed.forEach { plugin ->
                    plugin.folders.forEach { File(pluginsDir, it).deleteRecursively() }
                    info("Removed plugin '${plugin.declaration.name}'")
                }
                entries.filterNot { it.unchanged }.forEach { entry ->
                    previous[entry.declaration.name]?.folders?.forEach { File(pluginsDir, it).deleteRecursively() }
                    entry.roots.forEach { root ->
                        val target = File(pluginsDir, root.folder)
                        target.deleteRecursively()
                        place(root.dir, target)
                    }
                    info("Installed plugin '${entry.declaration.name}' from ${entry.declaration.path}: ${entry.roots.joinToString { it.folder }}")
                }
            } finally {
                discard()
            }
            return installed
        }

        /** removes extracted archives without installing them */
        fun discard() {
            staging.deleteRecursively()
        }

        private fun place(source: File, target: File) {
            pluginsDir.mkdirs()
            // extracted archives are moved, source folders copied
            if (source.startsWith(staging) && source.renameTo(target)) return
            source.copyRecursively(target, overwrite = true)
        }
    }

    /**
     * checks [declarations] against the [installed] state and extracts what has to be installed
     *
     * @param reinstall install every declaration again, also over folders not installed by this tool
     * @throws PluginInstallationException for missing or invalid sources, conflicts and incompatible plugins
     */
    fun prepare(declarations: List<PluginDeclaration>, installed: List<InstalledPlugin>, reinstall: Boolean = false): Plan {
        val staging = File(pluginsDir.parentFile, ".plugins-staging")
        // left over if writing the environment failed after the previous preparation
        staging.deleteRecursively()
        try {
            return plan(declarations, installed, reinstall, staging)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun plan(declarations: List<PluginDeclaration>, installed: List<InstalledPlugin>, reinstall: Boolean, staging: File): Plan {
        declarations.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw PluginInstallationException("Plugin '$it' is declared more than once")
        }
        val previous = installed.associateBy { it.declaration.name }
        val entries = declarations.map { entry(it, previous[it.name], reinstall, staging) }

        checkUnique(entries, "folder") { it.folder }
        checkUnique(entries, "plugin ID") { it.descriptor?.id }
        checkForeignFolders(entries, installed, reinstall)
        warnAboutDependencies(entries, installed)

        val removed = installed.filter { old -> declarations.none { it.name == old.declaration.name } }
        return Plan(entries, removed, previous, staging)
    }

    private fun entry(declaration: PluginDeclaration, previous: InstalledPlugin?, reinstall: Boolean, staging: File): Entry {
        val name = declaration.name
        val path = declaration.path
        val installedFolders = previous?.takeIf { it.declaration.path == path }?.folders?.takeIf { folders ->
            folders.isNotEmpty() && folders.all { File(pluginsDir, it).isDirectory }
        }
        if (!path.exists()) {
            // e.g. a cleaned up download folder: the installed copy is still fine
            if (installedFolders == null || reinstall) throw PluginInstallationException("Plugin '$name': ${declaration.source} not found ($path)")
            warn("Plugin '$name': ${declaration.source} not found ($path); keeping the installed copy")
            val roots = installedFolders.map { folder -> File(pluginsDir, folder).let { Root(it, folder, describe(it)) } }
            val warnings = mutableListOf<String>()
            val confirmedFor = checkCompatibility(declaration, roots, previous, warnings)
            return Entry(declaration, previous.fingerprint, roots, unchanged = true, confirmedFor, warnings)
        }

        val fingerprint = fingerprint(path)
        val unchanged = !reinstall && previous != null && previous.declaration.path == path &&
            previous.fingerprint == fingerprint && previous.folders.isNotEmpty() &&
            previous.folders.all { File(pluginsDir, it).isDirectory }

        val roots = when {
            unchanged -> previous!!.folders.map { folder -> File(pluginsDir, folder).let { Root(it, folder, describe(it)) } }
            path.isDirectory -> rootsOf(path, defaultFolder = path.name)
            else -> {
                val into = File(staging, name)
                try {
                    Archives.unzip(path, into)
                } catch (e: IOException) {
                    throw PluginInstallationException("Plugin '$name': unable to extract ${declaration.source}: ${e.message}")
                }
                rootsOf(into, defaultFolder = name)
            }
        }
        if (roots.isEmpty()) {
            throw PluginInstallationException(
                "Plugin '$name': no plugin in ${declaration.source} (expected META-INF/plugin.xml in a folder or in its lib/*.jar)"
            )
        }

        val warnings = mutableListOf<String>()
        val confirmedFor = checkCompatibility(declaration, roots, previous?.takeIf { it.fingerprint == fingerprint && it.declaration.path == path }, warnings)
        return Entry(declaration, fingerprint, roots, unchanged, confirmedFor, warnings)
    }

    /** a root at the top of an archive or source folder has no folder name of its own */
    private fun rootsOf(dir: File, defaultFolder: String): List<Root> =
        PluginDescriptor.findRoots(dir).map { Root(it, if (it == dir) defaultFolder else it.name, describe(it)) }

    private fun describe(root: File): PluginDescriptor? = try {
        PluginDescriptor.of(root)
    } catch (e: PluginInstallationException) {
        warn(e.message ?: "Unable to read the descriptor of $root")
        null
    }

    /** @return the MPS build an uncertain compatibility is confirmed for */
    private fun checkCompatibility(declaration: PluginDeclaration, roots: List<Root>, previous: InstalledPlugin?, warnings: MutableList<String>): String? {
        val results = roots.map { it to Compatibility.of(it.descriptor, build) }
        results.firstNotNullOfOrNull { (root, result) -> (result as? Compatibility.Incompatible)?.let { root to it } }?.let { (root, result) ->
            throw PluginInstallationException(
                "Plugin '${declaration.name}': ${label(declaration, root)} is not compatible: ${result.reason}. " +
                    "Point to the build of the plugin for this MPS version."
            )
        }
        val uncertain = results.mapNotNull { (root, result) -> (result as? Compatibility.Uncertain)?.let { "${label(declaration, root)}: ${it.reason}" } }
        if (uncertain.isEmpty() || declaration.compatible) return null

        val message = "Plugin '${declaration.name}' may not be compatible: ${uncertain.joinToString("; ")}"
        val buildNumber = build?.number ?: "unknown"
        val confirmed = previous?.confirmedFor == buildNumber || acceptUncertain(message)
        if (!confirmed) {
            throw PluginInstallationException("$message. Point to the build of the plugin for this MPS version, or declare it compatible.")
        }
        warnings += "$message (installed anyway)"
        return buildNumber
    }

    private fun label(declaration: PluginDeclaration, root: Root): String =
        root.descriptor?.id?.let { "${declaration.source} ($it)" } ?: declaration.source

    private fun checkUnique(entries: List<Entry>, what: String, key: (Root) -> String?) {
        entries.flatMap { entry -> entry.roots.mapNotNull { root -> key(root)?.let { it to entry.declaration.name } } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .entries.firstOrNull()?.let { (value, names) ->
                throw PluginInstallationException("The $what $value is contained in more than one plugin declaration: ${names.distinct().joinToString()}")
            }
    }

    /** folders installed otherwise must not be replaced silently */
    private fun checkForeignFolders(entries: List<Entry>, installed: List<InstalledPlugin>, reinstall: Boolean) {
        if (reinstall) return
        val managed = installed.flatMap { it.folders }.toSet()
        entries.filterNot { it.unchanged }.forEach { entry ->
            entry.roots.firstOrNull { it.folder !in managed && File(pluginsDir, it.folder).exists() }?.let { root ->
                throw PluginInstallationException(
                    "Plugin '${entry.declaration.name}': ${File(pluginsDir, root.folder)} exists and was not installed by mps-env-gen. " +
                        "Remove it or reinstall the plugins."
                )
            }
        }
    }

    private fun warnAboutDependencies(entries: List<Entry>, installed: List<InstalledPlugin>) {
        val bundled = PluginDescriptor.bundledIds(mpsHome)
        val managed = installed.flatMap { it.folders }.toSet() + entries.flatMap { e -> e.roots.map { it.folder } }
        val others = pluginsDir.listFiles { f -> f.isDirectory && f.name !in managed }.orEmpty()
            .mapNotNull { runCatching { PluginDescriptor.of(it) }.getOrNull()?.id }
        val available = bundled + others + entries.flatMap { e -> e.roots.mapNotNull { it.descriptor?.id } }
        val libraries = projectPath?.let { File(it, ".mps/libraries.xml") }?.takeIf { it.isFile }

        entries.forEach { entry ->
            entry.roots.forEach { root ->
                val id = root.descriptor?.id
                val label = "Plugin '${entry.declaration.name}'" + (id?.let { " ($it)" } ?: "")
                if (bundled.isNotEmpty() && id != null && id in bundled) {
                    entry.warnings += "$label replaces the plugin of the same ID bundled with MPS"
                }
                root.descriptor?.depends.orEmpty()
                    // module aliases and the platform itself are always provided by MPS
                    .filterNot { it == "com.intellij" || it.startsWith("com.intellij.modules.") }
                    .filter { bundled.isNotEmpty() && it !in available }
                    .forEach { entry.warnings += "$label needs $it, which is neither bundled with MPS nor installed" }
                if (libraries != null && File(root.dir, "languages").isDirectory) {
                    entry.warnings += "$label contains MPS languages and the project declares libraries in $libraries; " +
                        "modules may be loaded twice"
                }
            }
            entry.warnings.distinct().forEach(warn)
        }
    }

    companion object {
        /** size and modification time: detects a replaced zip or changed folder without reading contents */
        fun fingerprint(path: File): String =
            if (path.isDirectory) {
                val files = path.walkTopDown().filter { it.isFile }.toList()
                "dir:${files.size}:${files.sumOf { it.length() }}:${files.maxOfOrNull { it.lastModified() } ?: 0}"
            } else {
                "file:${path.length()}:${Files.getLastModifiedTime(path.toPath()).toMillis()}"
            }
    }
}
