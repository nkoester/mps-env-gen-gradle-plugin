package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.environment.Theme
import de.itemis.mps.envgen.plugins.PluginDeclaration
import de.itemis.mps.envgen.plugins.PluginFile
import de.itemis.mps.envgen.plugins.PluginInstallationException
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

class MpsEnvironmentGenerationPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("mpsEnvironments", MpsEnvironmentsExtension::class.java)
        extension.targetPath.convention(project.layout.projectDirectory.dir(".mpsconfig"))
        extension.mpsInTargetPath.convention(false)

        extension.environments.configureEach {
            if (Platform.isReservedEnvironmentName(name)) {
                throw GradleException("Environment name '$name' is reserved for prepared MPS distributions (mps-<version>-<OS>, jbr-<version>-<classifier>).")
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
            updateChecks.convention(true)
            mergeDriverNotification.convention(false)
            selectOpenedFile.convention(true)
        }

        val distributions = DistributionTasks(project, extension)
        val generateAll = project.tasks.register("generateMpsEnvironmentAll") {
            group = "mps"
            description = "Generates all isolated MPS environments."
        }

        extension.environments.all {
            val task = registerGenerateTask(project, extension, this, distributions)
            generateAll.configure { dependsOn(task) }
        }

        // prepare tasks are per MPS version and JBR, which are only known once the build script ran
        project.afterEvaluate {
            extension.environments.forEach { environment ->
                (mpsSource(environment, extension) as? MpsSource.Version)?.let {
                    distributions.forVersion(it.version)
                    distributions.jbrFor(it.version, environment.jbrVersion.orNull)
                }
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
        val jbr: Provider<PrepareJbrTask> = source.flatMap { s ->
            (s as? MpsSource.Version)?.let { v -> distributions.jbrNamed(v.version, environment.jbrVersion.orNull) } ?: project.provider { null }
        }

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
            javaHome.set(environment.javaHome.orElse(jbr.flatMap { it.javaHome }))
            dependsOn(source.map { s ->
                if (s is MpsSource.Version) listOf<Any>(distributions.named(s.version), distributions.jbrNamed(s.version, environment.jbrVersion.orNull))
                else emptyList()
            })
            missingMpsHint.set(source.map { s ->
                if (s is MpsSource.Version) "Prepare it via: ./gradlew ${distributions.taskName(s.version)}" else "Check 'mpsPath' in your Gradle build."
            })
            missingJavaHint.set(source.map { s ->
                if (s is MpsSource.Version && !environment.javaHome.isPresent) {
                    "Prepare it via: ./gradlew ${distributions.jbrTaskName(s.version, environment.jbrVersion.orNull)}"
                } else {
                    "Check 'javaHome' in your Gradle build."
                }
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
            updateChecks.set(environment.updateChecks)
            mergeDriverNotification.set(environment.mergeDriverNotification)
            selectOpenedFile.set(environment.selectOpenedFile)
            vmOptions.set(environment.vmOptions)
            ideaProperties.set(environment.ideaProperties)
            plugins.set(project.provider {
                PluginFile.merge(
                    readPluginsFile(project, extension.pluginsFile), declarations(extension.plugins),
                    readPluginsFile(project, environment.pluginsFile), declarations(environment.plugins),
                )
            })
        }
    }

    /** read via the providers API, so the configuration cache notices changes of the file */
    private fun readPluginsFile(project: Project, file: RegularFileProperty): List<PluginDeclaration> {
        val location = file.orNull?.asFile ?: return emptyList()
        if (!location.isFile) throw GradleException("Plugin file $location not found")
        val text = project.providers.fileContents(file).asText.get()
        return try {
            PluginFile.parse(text, location.absoluteFile.parentFile, location.path)
        } catch (e: PluginInstallationException) {
            throw GradleException(e.message ?: "Invalid plugin file $location", e)
        }
    }

    private fun declarations(plugins: NamedDomainObjectContainer<MpsPlugin>): List<PluginDeclaration> = plugins.map { plugin ->
        val source = plugin.source.orNull ?: throw GradleException("Plugin '${plugin.name}': set its zip or folder with from(...)")
        try {
            PluginDeclaration(
                name = plugin.name, source = source.path, path = source.absoluteFile.normalize(),
                systemProperties = plugin.systemProperties.get(), ports = plugin.ports.get(),
                compatible = plugin.compatible.getOrElse(false),
            )
        } catch (e: IllegalArgumentException) {
            throw GradleException(e.message ?: "Invalid plugin '${plugin.name}'", e)
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
