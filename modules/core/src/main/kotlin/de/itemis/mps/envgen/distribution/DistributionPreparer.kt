package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import java.io.File
import java.util.Properties

/** content of the marker file written into every prepared distribution */
data class PreparedDistribution(
    val root: File,
    val platform: Platform,
    val mpsVersion: MpsVersion,
    val jbrVersion: JbrVersion,
    val launcher: Launcher,
    val warnings: List<String>,
) {
    val home: File get() = File(root, platform.homePath).normalize()
    val javaHome: File get() = DistributionLayout.javaHome(home, platform)

    companion object {
        const val MARKER_FILE = ".mps-env-gen.properties"
    }
}

class DistributionPreparationException(message: String) : RuntimeException(message)

/**
 * Assembles a runnable MPS in [PreparedDistribution.root]. Unpacking is passed in, so callers can use their own
 * archive support (Gradle `zipTree`/`tarTree`, plain JDK, ...). The target is replaced completely.
 */
object DistributionPreparer {

    /** prepares from the generic MPS zip and the JBR tar.gz as published on Nexus */
    fun prepare(target: File, platform: Platform, mpsVersion: MpsVersion, jbrVersion: JbrVersion, mpsArchive: File, jbrArchive: File) =
        prepare(
            target, platform, mpsVersion, jbrVersion,
            unpackMps = { Archives.unzip(mpsArchive, it) },
            unpackJbr = { Archives.untarGz(jbrArchive, it) },
        )

    /** reads the marker of a prepared distribution; null if [root] holds none or a broken one */
    fun read(root: File): PreparedDistribution? {
        val marker = File(root, PreparedDistribution.MARKER_FILE).takeIf { it.isFile } ?: return null
        return runCatching {
            val props = Properties().apply { marker.reader().use { load(it) } }
            val platform = Platform.of(OS.valueOf(props.getProperty("os")), Arch.valueOf(props.getProperty("arch")))
            PreparedDistribution(
                root, platform,
                MpsVersion.parse(props.getProperty("mpsVersion")),
                JbrVersion.parse(props.getProperty("jbrVersion")),
                Launcher(Launcher.Kind.valueOf(props.getProperty("launcher.kind")), props.getProperty("launcher.path")),
                warnings = emptyList(),
            )
        }.getOrNull()
    }

    fun prepare(
        target: File,
        platform: Platform,
        mpsVersion: MpsVersion,
        jbrVersion: JbrVersion,
        unpackMps: (into: File) -> Unit,
        unpackJbr: (into: File) -> Unit,
    ): PreparedDistribution {
        if (target.exists() && !target.deleteRecursively()) {
            throw DistributionPreparationException("Unable to delete $target. Is MPS still running from it?")
        }
        val home = File(target, platform.homePath).normalize().apply { mkdirs() }

        unpackMps(home)
        DistributionLayout.stripSingleTopLevelDirectory(home, DistributionLayout.MPS_CONTENT)
        DistributionLayout.applyLayout(home, platform)

        val jbr = File(home, Platform.JBR_DIR).apply { mkdirs() }
        unpackJbr(jbr)
        DistributionLayout.stripSingleTopLevelDirectory(jbr, DistributionLayout.JBR_CONTENT)

        DistributionLayout.setExecutableBits(home)

        val validation = DistributionLayout.validate(home, platform)
        if (!validation.isValid) {
            throw DistributionPreparationException(
                "Prepared MPS $mpsVersion in $target is not runnable:\n" + validation.errors.joinToString("\n") { " - $it" }
            )
        }

        val prepared = PreparedDistribution(
            target, platform, mpsVersion, jbrVersion,
            DistributionLayout.selectLauncher(home, platform), validation.warnings,
        )
        writeMarker(prepared)
        return prepared
    }

    private fun writeMarker(prepared: PreparedDistribution) {
        val props = Properties().apply {
            setProperty("mpsVersion", prepared.mpsVersion.toString())
            setProperty("jbrVersion", prepared.jbrVersion.toString())
            setProperty("os", prepared.platform.os.name)
            setProperty("arch", prepared.platform.arch.name)
            setProperty("home", prepared.platform.homePath)
            setProperty("launcher.kind", prepared.launcher.kind.name)
            setProperty("launcher.path", prepared.launcher.path)
        }
        File(prepared.root, PreparedDistribution.MARKER_FILE).outputStream().use {
            props.store(it, "written by mps-env-gen-gradle-plugin, do not edit")
        }
    }
}
