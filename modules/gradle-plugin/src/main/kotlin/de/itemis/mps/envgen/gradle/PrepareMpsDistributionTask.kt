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

/** assembles a runnable MPS for the host platform from the generic Nexus zip and a Nexus JBR */
@DisableCachingByDefault(because = "Output is a large unpacked copy of artifacts already in the Gradle dependency cache")
abstract class PrepareMpsDistributionTask : DefaultTask() {

    @get:Input
    abstract val mpsVersion: Property<String>

    @get:Input
    abstract val jbrVersion: Property<String>

    @get:Input
    abstract val os: Property<OS>

    @get:Input
    abstract val arch: Property<Arch>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mpsArchive: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jbrArchive: ConfigurableFileCollection

    /** `<targetPath>/mps-<version>-<OS>` */
    @get:OutputDirectory
    abstract val distributionDir: DirectoryProperty

    /** MPS home inside [distributionDir] (`MPS.app/Contents` on macOS) */
    @get:Internal
    val mpsHome: Provider<Directory>
        get() = distributionDir.zip(platform()) { dir, platform -> if (platform.homePath == ".") dir else dir.dir(platform.homePath) }

    /** java home of the bundled JBR */
    @get:Internal
    val javaHome: Provider<Directory>
        get() = distributionDir.zip(platform()) { dir, platform ->
            dir.dir(listOf(platform.homePath, Platform.JBR_DIR, platform.javaHome).filter { it != "." }.joinToString("/"))
        }

    private fun platform(): Provider<Platform> = os.zip(arch) { os, arch -> Platform.of(os, arch) }

    @TaskAction
    fun prepare() {
        val platform = platform().get()
        val target = distributionDir.get().asFile
        logger.lifecycle("Preparing MPS ${mpsVersion.get()} with JBR ${jbrVersion.get()} (${platform.jbrClassifier}) in $target")

        // same JDK based extraction as the CLI; keeps JBR file modes and symlinks
        val prepared = DistributionPreparer.prepare(
            target, platform, MpsVersion.parse(mpsVersion.get()), JbrVersion.parse(jbrVersion.get()),
            mpsArchive = mpsArchive.singleFile, jbrArchive = jbrArchive.singleFile,
        )
        prepared.warnings.forEach { logger.warn("Warning: $it") }
        logger.lifecycle("MPS ${prepared.mpsVersion} prepared; launcher: ${prepared.launcher.path} (${prepared.launcher.kind.name.lowercase()})")
    }
}
