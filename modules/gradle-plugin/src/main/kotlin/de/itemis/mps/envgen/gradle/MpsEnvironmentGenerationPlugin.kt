package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.environment.Theme
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

class MpsEnvironmentGenerationPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("mpsEnvironments", MpsEnvironmentsExtension::class.java)
        extension.targetPath.convention(project.layout.projectDirectory.dir(".mpsconfig"))
        extension.mpsInTargetPath.convention(false)

        extension.environments.configureEach {
            if (Platform.isReservedEnvironmentName(name)) {
                throw GradleException("Environment name '$name' is reserved for prepared MPS distributions (mps-<version>-<OS>).")
            }
            // mpsPath/mpsVersion have no conventions: an environment's version must win over a default path
            jbrVersion.convention(extension.jbrVersion)
            javaHome.convention(extension.javaHome)
            projectPath.convention(extension.projectPath)
            os.convention(listOf(OS.current))
            xms.convention("1024m")
            xmx.convention("2048m")
            ratio.convention(4)
            debug.convention(false)
            debugPort.convention(5071)
            debugSuspend.convention(false)
            theme.convention(Theme.LIGHT)
            modelCheckBeforeGeneration.convention(false)
        }

        val distributions = DistributionTasks(project, extension)
        val generateAll = project.tasks.register("generateMpsEnvironmentAll") {
            group = "mps"
            description = "Generates ALL isolated MPS configuration prefixes."
        }

        extension.environments.all {
            val task = registerGenerateTask(project, extension, this, distributions)
            generateAll.configure { dependsOn(task) }
        }

        // prepare tasks are per MPS version, which is only known once the build script ran
        project.afterEvaluate {
            extension.environments.forEach { environment ->
                (mpsSource(environment, extension) as? MpsSource.Version)?.let { distributions.forVersion(it.version) }
            }
        }
    }

    private fun registerGenerateTask(
        project: Project,
        extension: MpsEnvironmentsExtension,
        environment: MpsEnvironment,
        distributions: DistributionTasks,
    ): TaskProvider<GenerateMpsEnvironmentTask> {
        val taskName = "generateMpsEnvironment-" + environment.name.replaceFirstChar { it.uppercase() }
        val source = project.provider { mpsSource(environment, extension) }
        val prepare: Provider<PrepareMpsDistributionTask> =
            source.flatMap { (it as? MpsSource.Version)?.let { v -> distributions.named(v.version) } ?: project.provider { null } }

        return project.tasks.register(taskName, GenerateMpsEnvironmentTask::class.java) {
            group = "mps"
            description = "Generates the isolated MPS environment '${environment.name}'."

            targetPath.set(extension.targetPath)
            environmentName.set(environment.name)
            mpsPath.set(source.flatMap { s ->
                when (s) {
                    is MpsSource.Path -> s.path
                    is MpsSource.Version -> distributions.named(s.version).flatMap { it.mpsHome }
                }
            })
            javaHome.set(environment.javaHome.orElse(prepare.flatMap { it.javaHome }))
            dependsOn(source.map { s -> if (s is MpsSource.Version) listOf<Any>(distributions.named(s.version)) else emptyList() })
            missingMpsHint.set(source.map { s ->
                if (s is MpsSource.Version) "Prepare it via: ./gradlew ${distributions.taskName(s.version)}" else "Check 'mpsPath' in your Gradle build."
            })

            os.set(environment.os)
            projectPath.set(environment.projectPath)
            xms.set(environment.xms)
            xmx.set(environment.xmx)
            ratio.set(environment.ratio)
            httpPort.set(environment.httpPort)
            debug.set(environment.debug)
            debugPort.set(environment.debugPort)
            debugSuspend.set(environment.debugSuspend)
            theme.set(environment.theme)
            modelCheckBeforeGeneration.set(environment.modelCheckBeforeGeneration)
            vmOptions.set(environment.vmOptions)
            ideaProperties.set(environment.ideaProperties)
        }
    }

    private sealed interface MpsSource {
        data class Path(val path: Provider<Directory>) : MpsSource
        data class Version(val version: String) : MpsSource
    }

    /** environment values before defaults; explicit paths before versions */
    private fun mpsSource(environment: MpsEnvironment, extension: MpsEnvironmentsExtension): MpsSource = when {
        environment.mpsPath.isPresent -> MpsSource.Path(environment.mpsPath)
        environment.mpsVersion.isPresent -> MpsSource.Version(environment.mpsVersion.get())
        extension.mpsPath.isPresent -> MpsSource.Path(extension.mpsPath)
        extension.mpsVersion.isPresent -> MpsSource.Version(extension.mpsVersion.get())
        else -> throw GradleException(
            "Environment '${environment.name}': set either mpsPath or mpsVersion (in mpsEnvironments or the environment)."
        )
    }
}
