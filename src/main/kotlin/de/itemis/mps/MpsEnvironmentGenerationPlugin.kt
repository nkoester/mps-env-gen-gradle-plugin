package de.itemis.mps

import de.itemis.mps.MpsConfigurationGenerationSettings
import org.gradle.api.*
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.typeOf
import java.util.*


class MpsEnvironmentGenerationPlugin : Plugin<Project> {

    override fun apply(project: Project) {

//        val objects: ObjectFactory = project.objects
//        val serverEnvironmentContainer: NamedDomainObjectContainer<MpsConfigurationGenerationSettings> = objects.domainObjectContainer(MpsConfigurationGenerationSettings::class.java)
//        project.extensions.add("mpsEnvironments", serverEnvironmentContainer)
//        serverEnvironmentContainer.forEach({ settingsExtension ->

        val settingsExtension: MpsConfigurationGenerationSettings = project.extensions.create("mpsEnvironments", MpsConfigurationGenerationSettings::class.java)
        // we have to run after our build script was evaluated
        project.afterEvaluate {
            logger.lifecycle("Configuring new task(s) for environment(s) ${settingsExtension.environmentList.map { it.environmentName }}")

            val allGeneratedTasks = settingsExtension.environmentList.map { leEnvironment ->
                val taskName = "generateMpsEnvironment-" + leEnvironment.environmentName.replaceFirstChar { it.uppercase() }

                project.tasks.register(taskName, GenerateMpsEnvironmentTask::class.java) {
                    group = "mps"
                    description = "Generates an isolated MPS configuration prefix."

                    // local definitions take precedence over the global ones
                    mpsPath.set(leEnvironment.mpsPathLocal.orElse(settingsExtension.mpsPath))
                    mpsProjectPath.set(leEnvironment.mpsProjectPathLocal.orElse(settingsExtension.mpsProjectPath))
                    javaHome.set(settingsExtension.javaHome)

                    environmentName.set(leEnvironment.environmentName)
                    osToGenerate.set(leEnvironment.osToGenerate)

                    configBasePath.set(settingsExtension.targetPath)

                    disableModelCheckBeforeGeneration.set(leEnvironment.mpsSettings.disableModelCheckBeforeGeneration)
                    lightTheme.set(leEnvironment.mpsSettings.lightTheme)
                    xms.set(leEnvironment.mpsSettings.xms)
                    xmx.set(leEnvironment.mpsSettings.xmx)
                    ratioValue.set(leEnvironment.mpsSettings.ratio)
                    httpPort.set(leEnvironment.mpsSettings.httpPort)
                    extraVmArgs.set(leEnvironment.mpsSettings.extraVmmArgs)
                    debugEnable.set(leEnvironment.mpsSettings.debugEnabled)
                    debugPort.set(leEnvironment.mpsSettings.debugPort)
                    debugSuspend.set(leEnvironment.mpsSettings.debugSuspend)

                    extraIdeaArgs.set(leEnvironment.ideaSettings.extraIdeaArgs)
                }
            }

            // generate a helper task to generate all environments defined
            project.tasks.register("generateMpsEnvironmentAll", Task::class.java) {
                group = "mps"
                description = "Generates ALL isolated MPS configuration prefixes."
                dependsOn(allGeneratedTasks)
            }

//        })
        }
    }
}
