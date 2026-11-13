package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.Arch
import de.itemis.mps.envgen.distribution.DistributionPreparer
import de.itemis.mps.envgen.distribution.JbrVersion
import de.itemis.mps.envgen.distribution.MpsVersion
import de.itemis.mps.envgen.distribution.Platform
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** assembles a runnable MPS (without JBR, see [PrepareJbrTask]) for the host platform from the generic Nexus zip */
@DisableCachingByDefault(because = "Output is a large unpacked copy of artifacts already in the Gradle dependency cache")
abstract class PrepareMpsDistributionTask : DefaultTask() {

    @get:Input
    abstract val mpsVersion: Property<String>

    /** default JBR of this MPS version, recorded with the prepared MPS */
    @get:Input
    abstract val jbrVersion: Property<String>

    @get:Input
    abstract val os: Property<OS>

    @get:Input
    abstract val arch: Property<Arch>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mpsArchive: ConfigurableFileCollection

    /** `<cache>/distributions/mps-<version>-<OS>`, or `<targetPath>/mps-<version>-<OS>` */
    @get:OutputDirectory
    abstract val distributionDir: DirectoryProperty

    /** MPS home inside [distributionDir] (`MPS.app/Contents` on macOS) */
    @get:Internal
    val mpsHome: Provider<Directory>
        get() = distributionDir.zip(platform()) { dir, platform -> if (platform.homePath == ".") dir else dir.dir(platform.homePath) }

    private fun platform(): Provider<Platform> = os.zip(arch) { os, arch -> Platform.of(os, arch) }

    @TaskAction
    fun prepare() {
        val platform = platform().get()
        val target = distributionDir.get().asFile
        val version = MpsVersion.parse(mpsVersion.get())
        // the folder may be shared with other projects and the CLI, and in use: a prepared MPS is never replaced
        DistributionPreparer.read(target)?.takeIf { it.platform == platform && it.mpsVersion == version }?.let {
            logger.lifecycle("Using prepared MPS $version in $target")
            return
        }
        logger.lifecycle("Preparing MPS $version in $target")
        val prepared = DistributionPreparer.prepareMpsOrReuse(
            target, platform, version, defaultJbr = { JbrVersion.parse(jbrVersion.get()) }, mpsArchive = { mpsArchive.singleFile },
        )
        prepared.warnings.forEach { logger.warn("Warning: $it") }
        logger.lifecycle("MPS ${prepared.mpsVersion} prepared; launcher: ${prepared.launcher.path} (${prepared.launcher.kind.name.lowercase()})")
    }
}

/** unpacks a Nexus JBR for the host platform into a folder of its own, shared by all MPS versions using it */
@DisableCachingByDefault(because = "Output is a large unpacked copy of artifacts already in the Gradle dependency cache")
abstract class PrepareJbrTask : DefaultTask() {

    @get:Input
    abstract val jbrVersion: Property<String>

    @get:Input
    abstract val os: Property<OS>

    @get:Input
    abstract val arch: Property<Arch>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jbrArchive: ConfigurableFileCollection

    /** `<cache>/distributions/jbr-<version>-<classifier>`, or the same in the target path */
    @get:OutputDirectory
    abstract val jbrDir: DirectoryProperty

    /** java home inside [jbrDir] (`Contents/Home` on macOS) */
    @get:Internal
    val javaHome: Provider<Directory>
        get() = jbrDir.zip(platform()) { dir, platform -> if (platform.javaHome == ".") dir else dir.dir(platform.javaHome) }

    private fun platform(): Provider<Platform> = os.zip(arch) { os, arch -> Platform.of(os, arch) }

    @TaskAction
    fun prepare() {
        val platform = platform().get()
        val target = jbrDir.get().asFile
        val version = JbrVersion.parse(jbrVersion.get())
        DistributionPreparer.readJbr(target)?.takeIf { it.platform == platform && it.version == version }?.let {
            logger.lifecycle("Using prepared JBR $version in $target")
            return
        }
        logger.lifecycle("Preparing JBR $version (${platform.jbrClassifier}) in $target")
        DistributionPreparer.prepareJbrOrReuse(target, platform, version) { jbrArchive.singleFile }
        logger.lifecycle("JBR $version prepared")
    }
}
