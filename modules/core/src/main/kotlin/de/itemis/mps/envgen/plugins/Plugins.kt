package de.itemis.mps.envgen.plugins

import java.io.File

/** a plugin as declared by the user: a zip or folder containing one or more plugins */
data class PluginDeclaration(
    /** unique per environment; no dots, so it can prefix keys in properties files */
    val name: String,
    /** zip or folder as given by the user */
    val source: String,
    /** [source] resolved to an absolute path */
    val path: File,
    /** system properties for `mps64.vmoptions` */
    val systemProperties: Map<String, String> = emptyMap(),
    /** system properties holding a port; fixed, the start scripts refuse to start MPS if one is busy */
    val ports: Map<String, Int> = emptyMap(),
    /** the user vouches for compatibility: no question or warning for uncertain cases, definite mismatches still fail */
    val compatible: Boolean = false,
) {
    init {
        require(NAME.matches(name)) { "Invalid plugin name '$name': use letters, digits, '_' and '-'" }
    }

    companion object {
        val NAME = Regex("[A-Za-z0-9_-]+")
    }
}

/** a declaration as installed into an environment; stored in its `environment.properties` */
data class InstalledPlugin(
    val declaration: PluginDeclaration,
    /** detects changes of the source without comparing contents */
    val fingerprint: String,
    /** top-level folders in the plugins folder of the environment */
    val folders: List<String>,
    /** plugin IDs, in the order of [folders] */
    val ids: List<String>,
    /** MPS build the user confirmed an uncertain compatibility for; asked again for another build */
    val confirmedFor: String? = null,
    /** found at installation, repeated by the start scripts */
    val warnings: List<String> = emptyList(),
)

class PluginInstallationException(message: String) : RuntimeException(message)
