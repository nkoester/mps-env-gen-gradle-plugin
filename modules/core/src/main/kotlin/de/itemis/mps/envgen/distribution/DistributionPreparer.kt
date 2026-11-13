package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties

/**
 * MPS prepared by mps-env-gen, without JBR (see [PreparedJbr]). [jbrVersion] is the JBR resolved for this MPS version
 * when it was prepared: the default JBR of environments using it, known without network access.
 */
data class PreparedDistribution(
    val root: File,
    val platform: Platform,
    val mpsVersion: MpsVersion,
    val jbrVersion: JbrVersion,
    val launcher: Launcher,
    val warnings: List<String>,
) {
    val home: File get() = File(root, platform.homePath).normalize()

    companion object {
        const val MARKER_FILE = ".mps-env-gen.properties"
    }
}

/** JBR prepared by mps-env-gen in a folder of its own ([Platform.jbrDirName]), shared by all MPS versions using it */
data class PreparedJbr(val root: File, val platform: Platform, val version: JbrVersion) {
    val javaHome: File get() = DistributionLayout.javaHome(root, platform)
}

class DistributionPreparationException(message: String) : RuntimeException(message)

/**
 * Assembles a runnable MPS and a JBR, each in a folder of its own. Unpacking is passed in, so callers can use their
 * own archive support. Prepared folders may be shared (cache folder, several projects, CLI and Gradle) and in use by a
 * running MPS: a prepared folder is never replaced, only an incomplete one (no marker) is.
 */
object DistributionPreparer {

    private const val KIND_JBR = "jbr"

    /** prepares MPS from the generic zip as published on Nexus */
    fun prepareMps(target: File, platform: Platform, mpsVersion: MpsVersion, defaultJbr: JbrVersion, mpsArchive: File) =
        prepareMps(target, platform, mpsVersion, defaultJbr, unpackMps = { Archives.unzip(mpsArchive, it) })

    /** prepares a JBR from its tar.gz as published on Nexus */
    fun prepareJbr(target: File, platform: Platform, version: JbrVersion, jbrArchive: File) =
        prepareJbr(target, platform, version, unpackJbr = { Archives.untarGz(jbrArchive, it) })

    /**
     * [target] if it already holds MPS [mpsVersion] for [platform], otherwise prepared from the archive.
     * [defaultJbr] is only resolved for a new preparation. Serialised by a hidden lock file next to [target], which
     * stays: deleting it would let a waiting and a new process lock different files.
     */
    fun prepareMpsOrReuse(
        target: File, platform: Platform, mpsVersion: MpsVersion, defaultJbr: () -> JbrVersion, mpsArchive: () -> File,
    ): PreparedDistribution = locked(target) {
        read(target)?.let { existing ->
            if (existing.platform != platform || existing.mpsVersion != mpsVersion) throw DistributionPreparationException(
                "$target holds MPS ${existing.mpsVersion} for ${existing.platform.os}/${existing.platform.arch}, not $mpsVersion for " +
                    "${platform.os}/${platform.arch}. Remove it or use another cache folder."
            )
            return@locked existing
        }
        prepareMps(target, platform, mpsVersion, defaultJbr(), mpsArchive())
    }

    /** like [prepareMpsOrReuse] for the JBR [version] */
    fun prepareJbrOrReuse(target: File, platform: Platform, version: JbrVersion, jbrArchive: () -> File): PreparedJbr =
        locked(target) {
            readJbr(target)?.let { existing ->
                if (existing.platform != platform || existing.version != version) throw DistributionPreparationException(
                    "$target holds JBR ${existing.version} for ${existing.platform.jbrClassifier}, not $version for " +
                        "${platform.jbrClassifier}. Remove it or use another cache folder."
                )
                return@locked existing
            }
            prepareJbr(target, platform, version, jbrArchive())
        }

    /** reads the marker of a prepared MPS; null if [root] holds none or a broken one */
    fun read(root: File): PreparedDistribution? {
        val props = marker(root)?.takeIf { it.getProperty("kind") == null } ?: return null
        return runCatching {
            PreparedDistribution(
                root, platform(props),
                MpsVersion.parse(props.getProperty("mpsVersion")),
                JbrVersion.parse(props.getProperty("jbrVersion")),
                Launcher(Launcher.Kind.valueOf(props.getProperty("launcher.kind")), props.getProperty("launcher.path")),
                warnings = emptyList(),
            )
        }.getOrNull()
    }

    /** reads the marker of a prepared JBR; null if [root] holds none or a broken one */
    fun readJbr(root: File): PreparedJbr? {
        val props = marker(root)?.takeIf { it.getProperty("kind") == KIND_JBR } ?: return null
        return runCatching { PreparedJbr(root, platform(props), JbrVersion.parse(props.getProperty("jbrVersion"))) }.getOrNull()
    }

    /**
     * true if [javaHome] lies in a JBR or MPS folder prepared by mps-env-gen, i.e. was not chosen by the
     * user; by marker or, as the folder may have been removed with the cache, by folder name
     */
    fun isPrepared(javaHome: File): Boolean =
        generateSequence(javaHome.absoluteFile.normalize()) { it.parentFile }.take(5)
            .any { File(it, PreparedDistribution.MARKER_FILE).isFile || Platform.isReservedEnvironmentName(it.name) }

    fun prepareMps(
        target: File,
        platform: Platform,
        mpsVersion: MpsVersion,
        defaultJbr: JbrVersion,
        unpackMps: (into: File) -> Unit,
    ): PreparedDistribution {
        clearIncomplete(target)
        val home = File(target, platform.homePath).normalize().apply { mkdirs() }

        unpackMps(home)
        DistributionLayout.stripSingleTopLevelDirectory(home, DistributionLayout.MPS_CONTENT)
        DistributionLayout.applyLayout(home, platform)
        DistributionLayout.setExecutableBits(home)

        val validation = DistributionLayout.validate(home, platform)
        if (!validation.isValid) {
            throw DistributionPreparationException(
                "Prepared MPS $mpsVersion in $target is not runnable:\n" + validation.errors.joinToString("\n") { " - $it" }
            )
        }

        val prepared = PreparedDistribution(
            target, platform, mpsVersion, defaultJbr, DistributionLayout.selectLauncher(home, platform), validation.warnings,
        )
        writeMarker(target, prepared.platform) {
            setProperty("mpsVersion", prepared.mpsVersion.toString())
            setProperty("jbrVersion", prepared.jbrVersion.toString())
            setProperty("home", prepared.platform.homePath)
            setProperty("launcher.kind", prepared.launcher.kind.name)
            setProperty("launcher.path", prepared.launcher.path)
        }
        return prepared
    }

    fun prepareJbr(target: File, platform: Platform, version: JbrVersion, unpackJbr: (into: File) -> Unit): PreparedJbr {
        clearIncomplete(target)
        target.mkdirs()
        unpackJbr(target)
        DistributionLayout.stripSingleTopLevelDirectory(target, DistributionLayout.JBR_CONTENT)

        val validation = DistributionLayout.validateJbr(target, platform)
        if (!validation.isValid) {
            throw DistributionPreparationException("Prepared JBR $version in $target is not usable:\n" + validation.errors.joinToString("\n") { " - $it" })
        }
        writeMarker(target, platform) {
            setProperty("kind", KIND_JBR)
            setProperty("jbrVersion", version.toString())
        }
        return PreparedJbr(target, platform, version)
    }

    private fun <T> locked(target: File, action: () -> T): T {
        target.absoluteFile.parentFile.mkdirs()
        RandomAccessFile(File(target.absoluteFile.parentFile, ".${target.name}.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { return action() }
        }
    }

    /** a folder without marker is an interrupted preparation; anything with a marker is never deleted */
    private fun clearIncomplete(target: File) {
        if (!target.exists()) return
        if (File(target, PreparedDistribution.MARKER_FILE).isFile) {
            throw DistributionPreparationException("$target is already prepared and may be in use; it is not replaced.")
        }
        if (!target.deleteRecursively()) throw DistributionPreparationException("Unable to delete the incomplete $target.")
    }

    private fun marker(root: File): Properties? {
        val file = File(root, PreparedDistribution.MARKER_FILE).takeIf { it.isFile } ?: return null
        return runCatching { Properties().apply { file.reader().use { load(it) } } }.getOrNull()
    }

    private fun platform(props: Properties): Platform =
        Platform.of(OS.valueOf(props.getProperty("os")), Arch.valueOf(props.getProperty("arch")))

    private fun writeMarker(root: File, platform: Platform, content: Properties.() -> Unit) {
        val props = Properties().apply {
            setProperty("os", platform.os.name)
            setProperty("arch", platform.arch.name)
            content()
        }
        File(root, PreparedDistribution.MARKER_FILE).outputStream().use { props.store(it, "written by mps-env-gen, do not edit") }
    }
}
