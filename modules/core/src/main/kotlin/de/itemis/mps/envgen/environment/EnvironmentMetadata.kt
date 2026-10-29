package de.itemis.mps.envgen.environment

import de.itemis.mps.envgen.Constants
import de.itemis.mps.envgen.OS
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** settings of an environment as stored in its `environment.properties` */
data class StoredEnvironment(
    /** spec with the location of the environment; hints and generator version are the caller's */
    val spec: EnvironmentSpec,
    val created: String?,
    val refreshed: String?,
    val history: List<String>,
)

/**
 * `<environment>/environment.properties`: when and from what an environment was created, and all its settings,
 * so it can be refreshed or copied without repeating the original arguments.
 */
object EnvironmentMetadata {

    const val FILE_NAME = "environment.properties"

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun file(environmentDir: File) = File(environmentDir, FILE_NAME)

    /** environments of this tool or the Gradle plugin; older ones have no metadata file yet */
    fun isEnvironment(dir: File): Boolean =
        file(dir).isFile || File(dir, "mps/${Constants.IDEA_PROPERTIES_FILENAME}").isFile

    fun write(spec: EnvironmentSpec, historyEntry: String? = null) {
        val previous = read(spec.environmentDir, spec)
        val now = LocalDateTime.now().format(TIMESTAMP)
        val history = previous?.history.orEmpty() + listOfNotNull(historyEntry?.let { "$now $it" })

        val props = Properties()
        fun set(key: String, value: Any?) = value?.let { props.setProperty(key, it.toString()) }
        fun setList(key: String, values: List<String>) = values.forEachIndexed { i, v -> props.setProperty("$key.$i", v) }

        set("created", previous?.created ?: now)
        set("refreshed", now)
        set("generatorVersion", spec.generatorVersion)
        set("mps.origin", spec.mpsOrigin.name)
        set("mps.source", spec.mpsSource)
        set("mps.path", spec.mpsPath)
        set("jbr.version", spec.jbrVersion)
        set("javaHome", spec.javaHome)
        set("project", spec.projectPath)
        set("os", spec.os.joinToString(","))
        set("modelCheckBeforeGeneration", spec.modelCheckBeforeGeneration)
        set("theme", spec.theme)
        set("xms", spec.xms)
        set("xmx", spec.xmx)
        set("ratio", spec.ratio)
        set("httpPort", spec.httpPort)
        set("debug", spec.debug)
        set("debug.port", spec.debugPort)
        set("debug.suspend", spec.debugSuspend)
        setList("vmOption", spec.vmOptions)
        setList("ideaProperty", spec.ideaProperties)
        setList("history", history)

        spec.environmentDir.mkdirs()
        file(spec.environmentDir).outputStream().use { props.store(it, "written by mps-env-gen; used by 'mps-env-gen refresh/copy/rename'") }
    }

    /**
     * reads the environment in [environmentDir]; [template] provides what is not stored (hints, generator version)
     * and fallbacks for keys missing in older files. Null if there is no metadata.
     */
    fun read(environmentDir: File, template: EnvironmentSpec): StoredEnvironment? {
        val file = file(environmentDir).takeIf { it.isFile } ?: return null
        val props = Properties().apply { file.reader().use { load(it) } }
        fun get(key: String): String? = props.getProperty(key)?.takeIf { it.isNotEmpty() }
        fun list(key: String): List<String> =
            generateSequence(0) { it + 1 }.map { props.getProperty("$key.$it") }.takeWhile { it != null }.filterNotNull().toList()

        val dir = environmentDir.absoluteFile.normalize()
        val spec = template.copy(
            targetPath = dir.parentFile,
            environmentName = dir.name,
            os = get("os")?.split(",")?.map { OS.valueOf(it.trim()) } ?: template.os,
            mpsPath = get("mps.path")?.let(::File) ?: template.mpsPath,
            javaHome = get("javaHome")?.let(::File),
            projectPath = get("project")?.let(::File),
            modelCheckBeforeGeneration = get("modelCheckBeforeGeneration")?.toBoolean() ?: template.modelCheckBeforeGeneration,
            theme = get("theme")?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: template.theme,
            xms = get("xms") ?: template.xms,
            xmx = get("xmx") ?: template.xmx,
            ratio = get("ratio")?.toIntOrNull() ?: template.ratio,
            httpPort = get("httpPort")?.toIntOrNull() ?: template.httpPort,
            vmOptions = list("vmOption"),
            debug = get("debug")?.toBoolean() ?: template.debug,
            debugPort = get("debug.port")?.toIntOrNull() ?: template.debugPort,
            debugSuspend = get("debug.suspend")?.toBoolean() ?: template.debugSuspend,
            ideaProperties = list("ideaProperty"),
            mpsOrigin = get("mps.origin")?.let { runCatching { MpsOrigin.valueOf(it) }.getOrNull() } ?: template.mpsOrigin,
            mpsSource = get("mps.source") ?: template.mpsSource,
            jbrVersion = get("jbr.version"),
        )
        return StoredEnvironment(spec, get("created"), get("refreshed"), list("history"))
    }
}
