package de.itemis.mps

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task


class MpsEnvironmentGenerationPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val settings: MpsConfigurationGenerationSettings = project.extensions.create("mpsEnvironments", MpsConfigurationGenerationSettings::class.java)

        project.afterEvaluate {
            println("Configuring new task(s) for environment(s) ${settings.environmentList.map { it.environmentName }}")

            val allGeneratedTasks = mutableListOf<GenerateMpsEnvironmentTask>()
            settings.environmentList.forEach { leEnvironment ->
                val taskName = "generateMpsEnvironment" + leEnvironment.environmentName.substring(0, 1).uppercase() + leEnvironment.environmentName.substring(1)

                val newTask = project.tasks.register(taskName, GenerateMpsEnvironmentTask::class.java) {
                    group = "mps"
                    description = "Generates an isolated MPS configuration prefix."

                    // overwrite MPS path if local definition exists
                    if (leEnvironment.mpsPathLocal.isPresent){
                        mpsPath.set(leEnvironment.mpsPathLocal)
                    } else {
                        mpsPath.set(settings.mpsPath)
                    }

                    // overwrite project path if local definition exists
                    if (leEnvironment.mpsPathLocal.isPresent){
                        mpsProjectPath.set(leEnvironment.mpsProjectPathLocal)
                    }else{
                        mpsProjectPath.set(settings.mpsProjectPath)
                    }

                    environmentName.set(leEnvironment.environmentName)
                    osToGenerate.set(leEnvironment.osToGenerate)

                    configBasePath.set(
                        project.layout.projectDirectory.dir(
                            settings.targetPath.get().toString()
                        )
                    )

                    mpsProjectPath.set(settings.mpsProjectPath)
                    lightTheme.set(leEnvironment.mpsSettings.lightTheme)
                    xms.set(leEnvironment.mpsSettings.xms)
                    xmx.set(leEnvironment.mpsSettings.xmx)
                    ratioValue.set(leEnvironment.mpsSettings.ratio)
                    extraVmArgs.set(leEnvironment.mpsSettings.extraVmmArgs)
                    debugEnable.set(leEnvironment.mpsSettings.debugEnabled)
                    debugPort.set(leEnvironment.mpsSettings.debugPort)
                    debugSuspend.set(leEnvironment.mpsSettings.debugSuspend)

                    extraIdeaArgs.set(leEnvironment.ideaSettings.extraIdeaArgs)

                }
                allGeneratedTasks.add(newTask.get())
            }

            // generate a helper task to generate all environments defined
            project.tasks.register("generateMpsEnvironmentAll", Task::class.java) {
                group = "mps"
                description = "Generates ALL isolated MPS configuration prefixes."
                allGeneratedTasks.forEach { dependsOn(it)}
            }
        }
    }
}
