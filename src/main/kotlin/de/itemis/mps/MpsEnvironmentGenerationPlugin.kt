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
            println("Configuring new task(s) for environment(s) ${settingsExtension.environmentList.map { it.environmentName }}")

            val allGeneratedTasks = mutableListOf<GenerateMpsEnvironmentTask>()
            settingsExtension.environmentList.forEach { leEnvironment ->
                val taskName = "generateMpsEnvironment" + leEnvironment.environmentName.substring(0, 1)
                    .uppercase() + leEnvironment.environmentName.substring(1)

                val newTask = project.tasks.register(taskName, GenerateMpsEnvironmentTask::class.java) {
                    group = "mps"
                    description = "Generates an isolated MPS configuration prefix."

                    // overwrite MPS path if local definition exists
                    if (leEnvironment.mpsPathLocal.isPresent) {
                        mpsPath.set(leEnvironment.mpsPathLocal.get())
                    } else {
                        mpsPath.set(settingsExtension.mpsPath.get())
                    }

                    // set java home if present
                    if (settingsExtension.javaHome.isPresent) {
                        javaHome.set(settingsExtension.javaHome.get())
                    }

                    // overwrite project path if local definition exists
                    if (leEnvironment.mpsPathLocal.isPresent) {
                        mpsProjectPath.set(leEnvironment.mpsProjectPathLocal)
                    } else {
                        mpsProjectPath.set(settingsExtension.mpsProjectPath)
                    }

                    environmentName.set(leEnvironment.environmentName)
                    osToGenerate.set(leEnvironment.osToGenerate)

                    configBasePath.set(settingsExtension.targetPath)

                    mpsProjectPath.set(settingsExtension.mpsProjectPath)
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
                allGeneratedTasks.add(newTask.get())
            }

//                 generate a helper task to generate all environments defined
            project.tasks.register("generateMpsEnvironmentAll", Task::class.java) {
                group = "mps"
                description = "Generates ALL isolated MPS configuration prefixes."
                allGeneratedTasks.forEach { dependsOn(it) }
            }

//        })
        }
    }
}
