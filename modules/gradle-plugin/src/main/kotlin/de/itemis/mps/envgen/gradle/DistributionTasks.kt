package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.distribution.JbrResolutionException
import de.itemis.mps.envgen.distribution.MpsVersion
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.distribution.resolveJbr
import de.itemis.mps.envgen.distributionsDir
import de.itemis.mps.envgen.userCacheDir
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import java.io.File

/** registers one [PrepareMpsDistributionTask] per MPS version, shared by all environments using that version */
internal class DistributionTasks(private val project: Project, private val settings: MpsEnvironmentsExtension) {

    private val tasks = mutableMapOf<MpsVersion, TaskProvider<PrepareMpsDistributionTask>>()
    private val source = GradleArtifactSource(project)

    fun forVersion(version: String): TaskProvider<PrepareMpsDistributionTask> {
        val mpsVersion = parse(version)
        return tasks.getOrPut(mpsVersion) { register(mpsVersion) }
    }

    fun taskName(version: String) = "prepareMps-${parse(version)}"

    /** lazy lookup for task wiring; the task is registered by [forVersion] once the build script ran */
    fun named(version: String): TaskProvider<PrepareMpsDistributionTask> =
        project.tasks.named(taskName(version), PrepareMpsDistributionTask::class.java)

    private fun parse(version: String) = runCatching { MpsVersion.parse(version) }.getOrElse { throw GradleException(it.message!!, it) }

    private fun register(mpsVersion: MpsVersion): TaskProvider<PrepareMpsDistributionTask> {
        val platform = Platform.current
        // resolved once, only when the task is actually part of the build
        val jbr = lazy {
            try {
                source.resolveJbr(mpsVersion, platform, settings.jbrVersion.orNull)
                    .also { it.resolution.warning?.let { warning -> project.logger.warn("Warning: $warning") } }
            } catch (e: JbrResolutionException) {
                throw GradleException(e.message!!, e)
            }
        }

        return project.tasks.register(taskName(mpsVersion.toString()), PrepareMpsDistributionTask::class.java) {
            group = "mps"
            description = "Downloads MPS $mpsVersion and a matching JBR from Nexus and prepares a runnable MPS."
            this.mpsVersion.set(mpsVersion.toString())
            os.set(platform.os)
            arch.set(platform.arch)
            jbrVersion.set(project.provider { jbr.value.resolution.version.toString() })
            mpsArchive.from(project.provider { source.mpsArchive(mpsVersion) })
            jbrArchive.from(project.provider { jbr.value.archive })
            val name = Platform.distributionDirName(mpsVersion, platform.os)
            // shared with other projects and the CLI unless kept in the target path
            val providers = project.providers
            val inCache = project.layout.dir(providers.provider {
                File(distributionsDir(userCacheDir(getenv = { providers.environmentVariable(it).orNull })), name)
            })
            distributionDir.set(settings.mpsInTargetPath.flatMap { inTarget -> if (inTarget) settings.targetPath.dir(name) else inCache })
        }
    }
}
