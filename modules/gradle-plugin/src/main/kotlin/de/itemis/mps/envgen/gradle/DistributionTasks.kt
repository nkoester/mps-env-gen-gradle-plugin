package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.distribution.JbrResolutionException
import de.itemis.mps.envgen.distribution.MpsVersion
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.distribution.ResolvedJbr
import de.itemis.mps.envgen.distribution.resolveJbr
import de.itemis.mps.envgen.distributionsDir
import de.itemis.mps.envgen.userCacheDir
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * registers one [PrepareMpsDistributionTask] per MPS version and one [PrepareJbrTask] per (MPS version, explicit JBR),
 * shared by all environments using them. Task names are fixed before any network access; the JBR is resolved only when
 * a task is part of the build.
 */
internal class DistributionTasks(private val project: Project, private val settings: MpsEnvironmentsExtension) {

    private val mpsTasks = mutableMapOf<MpsVersion, TaskProvider<PrepareMpsDistributionTask>>()
    private val jbrTasks = mutableMapOf<Pair<MpsVersion, String?>, TaskProvider<PrepareJbrTask>>()
    private val source = GradleArtifactSource(project)
    private val platform = Platform.current

    fun forVersion(version: String): TaskProvider<PrepareMpsDistributionTask> {
        val mpsVersion = parse(version)
        return mpsTasks.getOrPut(mpsVersion) { registerMps(mpsVersion) }
    }

    /** [jbrVersion]: explicit JBR, null for the default of the MPS version */
    fun jbrFor(version: String, jbrVersion: String?): TaskProvider<PrepareJbrTask> {
        val mpsVersion = parse(version)
        return jbrTasks.getOrPut(mpsVersion to jbrVersion) { registerJbr(mpsVersion, jbrVersion) }
    }

    fun taskName(version: String) = "prepareMps-${parse(version)}"

    fun jbrTaskName(version: String, jbrVersion: String?) = "prepareJbr-${parse(version)}" + (jbrVersion?.let { "-$it" } ?: "")

    /** lazy lookups for task wiring; the tasks are registered by [forVersion] and [jbrFor] once the build script ran */
    fun named(version: String): TaskProvider<PrepareMpsDistributionTask> =
        project.tasks.named(taskName(version), PrepareMpsDistributionTask::class.java)

    fun jbrNamed(version: String, jbrVersion: String?): TaskProvider<PrepareJbrTask> =
        project.tasks.named(jbrTaskName(version, jbrVersion), PrepareJbrTask::class.java)

    private fun parse(version: String) = runCatching { MpsVersion.parse(version) }.getOrElse { throw GradleException(it.message!!, it) }

    private fun resolve(mpsVersion: MpsVersion, explicit: String?): ResolvedJbr = try {
        source.resolveJbr(mpsVersion, platform, explicit)
            .also { it.resolution.warning?.let { warning -> project.logger.warn("Warning: $warning") } }
    } catch (e: JbrResolutionException) {
        throw GradleException(e.message!!, e)
    }

    private fun registerMps(mpsVersion: MpsVersion): TaskProvider<PrepareMpsDistributionTask> {
        // the default JBR is recorded with the MPS; resolved once, only when the task is actually part of the build
        val defaultJbr = lazy { resolve(mpsVersion, null) }
        return project.tasks.register(taskName(mpsVersion.toString()), PrepareMpsDistributionTask::class.java) {
            group = "mps"
            description = "Downloads MPS $mpsVersion from Nexus and prepares a runnable MPS (the JBR: prepareJbr-$mpsVersion)."
            this.mpsVersion.set(mpsVersion.toString())
            os.set(platform.os)
            arch.set(platform.arch)
            jbrVersion.set(project.provider { defaultJbr.value.resolution.version.toString() })
            mpsArchive.from(project.provider { source.mpsArchive(mpsVersion) })
            distributionDir.set(target(Platform.distributionDirName(mpsVersion, platform.os)))
        }
    }

    private fun registerJbr(mpsVersion: MpsVersion, explicit: String?): TaskProvider<PrepareJbrTask> {
        val jbr = lazy { resolve(mpsVersion, explicit) }
        return project.tasks.register(jbrTaskName(mpsVersion.toString(), explicit), PrepareJbrTask::class.java) {
            group = "mps"
            description = "Downloads " + (explicit?.let { "JBR $it" } ?: "the default JBR of MPS $mpsVersion") +
                " from Nexus and unpacks it next to the prepared MPS distributions."
            os.set(platform.os)
            arch.set(platform.arch)
            jbrVersion.set(project.provider { jbr.value.resolution.version.toString() })
            jbrArchive.from(project.provider { jbr.value.archive })
            jbrDir.set(target(project.provider { Platform.jbrDirName(jbr.value.resolution.version, platform) }))
        }
    }

    private fun target(name: String): Provider<Directory> = target(project.provider { name })

    /** in the shared cache, or in the target path with mpsInTargetPath */
    private fun target(name: Provider<String>): Provider<Directory> {
        val providers = project.providers
        val inCache = project.layout.dir(name.map { File(distributionsDir(userCacheDir(getenv = { providers.environmentVariable(it).orNull })), it) })
        return settings.mpsInTargetPath.flatMap { inTarget -> if (inTarget) settings.targetPath.dir(name) else inCache }
    }
}
