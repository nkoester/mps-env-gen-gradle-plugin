package de.itemis.mps.envgen.cli

import com.github.ajalt.clikt.core.UsageError
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.DistributionPreparer
import de.itemis.mps.envgen.distribution.JbrResolutionException
import de.itemis.mps.envgen.distribution.MpsVersion
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.distribution.PreparedDistribution
import de.itemis.mps.envgen.distribution.resolveJbr
import de.itemis.mps.envgen.environment.MpsOrigin
import java.io.File

data class ResolvedMps(val home: File, val javaHome: File?, val origin: MpsOrigin, val source: String, val jbrVersion: String?)

/** turns `<mps>` (version or path) into an MPS home: explicit path, local installation, or prepared from Nexus */
class MpsResolver(
    private val options: MpsSourceOptions,
    private val services: CliServices,
    private val info: (String) -> Unit,
    private val verbose: (String) -> Unit,
    private val warn: (String) -> Unit,
) {

    /** [distributionTarget] receives prepared distributions (`mps-<version>-<OS>`), next to the environments */
    fun resolve(mps: String, distributionTarget: File): ResolvedMps {
        val path = File(mps)
        if (path.exists()) {
            if (!path.isDirectory) throw UsageError("MPS path '$mps' is not a directory")
            val home = path.absoluteFile.normalize()
            return ResolvedMps(home, null, MpsOrigin.PATH, home.path, null)
        }
        val installs = installs()
        installs?.let { LocalMpsLocator.byName(it, mps) }?.let {
            info("Using local MPS installation $it")
            return ResolvedMps(it.absoluteFile.normalize(), null, MpsOrigin.LOCAL, mps, null)
        }
        val version = runCatching { MpsVersion.parse(mps) }.getOrElse {
            throw UsageError("'$mps' is neither an existing MPS path, a folder in ${installs ?: "--installs"}, nor an MPS version like 2025.3")
        }
        installs?.let { base ->
            LocalMpsLocator.find(base, version, OS.current)?.let {
                info("Using local MPS installation $it")
                return ResolvedMps(it, null, MpsOrigin.LOCAL, version.toString(), null)
            }
            verbose("No MPS $version in $base")
        }
        if (options.noDownload) throw failure("MPS $version not found locally and --no-download is set")

        val prepared = prepared(version, distributionTarget)
        return ResolvedMps(prepared.home, prepared.javaHome, MpsOrigin.NEXUS, version.toString(), prepared.jbrVersion.toString())
    }

    /** prepared distribution of [version] in [distributionTarget]; reused if already there */
    fun prepared(version: MpsVersion, distributionTarget: File): PreparedDistribution {
        val platform = Platform.current
        val root = File(distributionTarget, Platform.distributionDirName(version, platform.os))
        DistributionPreparer.read(root)
            ?.takeIf { it.mpsVersion == version && (options.jbrVersion == null || it.jbrVersion.toString() == options.jbrVersion) }
            ?.let {
                verbose("Using prepared MPS $version in $root")
                return it
            }
        if (options.noDownload) throw failure("MPS $version is not prepared in $distributionTarget and --no-download is set")

        info("Preparing MPS $version from ${options.repository} in $root")
        val source = services.artifactSource(options.repository, options.cacheDir, verbose)
        val jbr = try {
            source.resolveJbr(version, platform, options.jbrVersion)
        } catch (e: JbrResolutionException) {
            throw failure(e.message!!)
        }
        jbr.resolution.warning?.let(warn)
        val prepared = DistributionPreparer.prepare(root, platform, version, jbr.resolution.version, source.mpsArchive(version), jbr.archive)
        prepared.warnings.forEach(warn)
        info("MPS $version prepared with JBR ${prepared.jbrVersion}")
        return prepared
    }

    private fun installs(): File? = options.installs ?: services.getenv("MPS_BASE_PATH")?.let(::File)
}
