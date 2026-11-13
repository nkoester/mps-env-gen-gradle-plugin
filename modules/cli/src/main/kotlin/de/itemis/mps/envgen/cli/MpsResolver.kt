package de.itemis.mps.envgen.cli

import com.github.ajalt.clikt.core.UsageError
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distributionsDir
import de.itemis.mps.envgen.distribution.DistributionPreparationException
import de.itemis.mps.envgen.distribution.DistributionPreparer
import de.itemis.mps.envgen.distribution.JbrVersion
import de.itemis.mps.envgen.distribution.JbrResolutionException
import de.itemis.mps.envgen.distribution.MpsVersion
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.distribution.PreparedDistribution
import de.itemis.mps.envgen.distribution.PreparedJbr
import de.itemis.mps.envgen.distribution.resolveJbr
import de.itemis.mps.envgen.environment.MpsOrigin
import java.io.File

/** [jbrExplicit]: [jbrVersion] was chosen by the user (--jbr-version), not the default of the MPS version */
data class ResolvedMps(
    val home: File, val javaHome: File?, val origin: MpsOrigin, val source: String, val jbrVersion: String?, val jbrExplicit: Boolean = false,
)

/** turns `<mps>` (version or path) into an MPS home: explicit path, local installation, or prepared from Nexus */
class MpsResolver(
    private val options: MpsSourceOptions,
    private val services: CliServices,
    private val info: (String) -> Unit,
    private val verbose: (String) -> Unit,
    private val warn: (String) -> Unit,
) {

    /**
     * MPS from Nexus is prepared in the cache (`<cache>/distributions/mps-<version>-<OS>`), its JBR next to it
     * (`jbr-<version>-<classifier>`), both shared by all environments; with --mps-in-target in [environmentParent],
     * the folder of the environment. [explicitJbr] replaces the default JBR of the MPS version.
     */
    fun resolve(mps: String, environmentParent: File, explicitJbr: String? = options.jbrVersion): ResolvedMps {
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
            throw UsageError("'$mps' is neither an existing MPS path, a folder in ${installs ?: "--installs"}, nor an MPS version like 2025.1.4")
        }
        installs?.let { base ->
            LocalMpsLocator.find(base, version, OS.current)?.let {
                info("Using local MPS installation $it")
                return ResolvedMps(it, null, MpsOrigin.LOCAL, version.toString(), null)
            }
            verbose("No MPS $version in $base")
        }
        if (options.noDownload) throw failure("MPS $version not found locally and --no-download is set")

        val prepared = prepared(version, environmentParent)
        val jbr = jbr(prepared, environmentParent, explicitJbr)
        return ResolvedMps(prepared.home, jbr.javaHome, MpsOrigin.NEXUS, version.toString(), jbr.version.toString(), explicitJbr != null)
    }

    /**
     * prepared MPS [version]; reused if already there. Its default JBR is resolved (network) only for a new preparation.
     * [inTarget]: in [environmentParent] instead of the cache
     */
    fun prepared(version: MpsVersion, environmentParent: File, inTarget: Boolean = options.mpsInTarget): PreparedDistribution {
        val platform = Platform.current
        val root = File(distributionsTarget(environmentParent, inTarget), Platform.distributionDirName(version, platform.os))
        DistributionPreparer.read(root)?.takeIf { it.mpsVersion == version && it.platform == platform }?.let {
            verbose("Using prepared MPS $version in $root")
            return it
        }
        if (options.noDownload) throw failure("MPS $version is not prepared in ${root.parentFile} and --no-download is set")

        info("Preparing MPS $version from ${options.repository} in $root")
        val prepared = prepare {
            DistributionPreparer.prepareMpsOrReuse(
                root, platform, version,
                defaultJbr = { resolveJbr(version, platform, null).resolution.version },
                mpsArchive = { source.mpsArchive(version) },
            )
        }
        prepared.warnings.forEach(warn)
        info("MPS $version prepared")
        return prepared
    }

    /**
     * prepared JBR for [mps]: [explicitJbr] or the default JBR recorded with the prepared MPS; reused if already there.
     * [inTarget]: next to the environments instead of the cache
     */
    fun jbr(mps: PreparedDistribution, environmentParent: File, explicitJbr: String?, inTarget: Boolean = options.mpsInTarget): PreparedJbr {
        val platform = Platform.current
        val version = explicitJbr?.let {
            JbrVersion.parseOrNull(it) ?: throw UsageError("Invalid JBR version '$it'. Expected e.g. 21.0.8-b1163.69")
        } ?: mps.jbrVersion
        val root = File(distributionsTarget(environmentParent, inTarget), Platform.jbrDirName(version, platform))
        // parsed versions: 21.0.8b1163.69 and 21.0.8-b1163.69 are the same JBR
        DistributionPreparer.readJbr(root)?.takeIf { it.version == version && it.platform == platform }?.let {
            verbose("Using prepared JBR $version in $root")
            return it
        }
        if (options.noDownload) throw failure("JBR $version is not prepared in ${root.parentFile} and --no-download is set")

        info("Preparing JBR $version from ${options.repository} in $root")
        return prepare {
            DistributionPreparer.prepareJbrOrReuse(root, platform, version) {
                if (explicitJbr != null) resolveJbr(mps.mpsVersion, platform, version.toString()).archive
                else source.jbrArchive(version, platform.jbrClassifier)
                    ?: throw failure("JBR $version (${platform.jbrClassifier}) is not published at ${options.repository}")
            }
        }.also { info("JBR $version prepared") }
    }

    private fun distributionsTarget(environmentParent: File, inTarget: Boolean): File =
        if (inTarget) environmentParent.absoluteFile.normalize() else distributionsDir(options.cacheDir)

    private val source by lazy { services.artifactSource(options.repository, File(options.cacheDir, "downloads"), verbose) }

    private fun resolveJbr(version: MpsVersion, platform: Platform, explicit: String?) = try {
        source.resolveJbr(version, platform, explicit).also { it.resolution.warning?.let(warn) }
    } catch (e: JbrResolutionException) {
        throw failure(e.message!!)
    }

    private fun <T> prepare(action: () -> T): T = try {
        action()
    } catch (e: DistributionPreparationException) {
        throw failure(e.message!!)
    }

    private fun installs(): File? = options.installs ?: services.getenv("MPS_BASE_PATH")?.let(::File)
}
