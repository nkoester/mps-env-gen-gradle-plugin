package de.itemis.mps

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

open class MpsConfigurationGenerationSettings @Inject constructor(val project: Project){

    @Input
    val mpsPath: DirectoryProperty = project.objects.directoryProperty()

    @Input
    val targetPath: DirectoryProperty = project.objects.directoryProperty().convention(project.layout.projectDirectory.dir(".mpsconfig"))

    @Input
    val mpsProjectPath: DirectoryProperty = project.objects.directoryProperty()

    @Input
    val environmentList: MutableSet<MpsEnvironment> = mutableSetOf()

    fun environment(environmentName : String, action: Action<MpsEnvironment>) {
        require(!environmentList.any { it.environmentName == environmentName }){
            "Environment with name '$environmentName' already exists. Environment names must be distinct."
        }

        val mpsEnvironment = MpsEnvironment(environmentName, project)
        action.execute(mpsEnvironment)
        environmentList.add(mpsEnvironment)
    }
}

class MpsEnvironment(val environmentName: String, val project: Project) {
    var mpsSettings :MpsSettings = MpsSettings(project)
    var ideaSettings : IdeaSettings = IdeaSettings(project)

    val osToGenerate: ListProperty<Utils.OS> = project.objects.listProperty<Utils.OS>().convention(listOf(Utils.currentOS))
    val mpsPathLocal: DirectoryProperty = project.objects.directoryProperty()
    val mpsProjectPathLocal: DirectoryProperty = project.objects.directoryProperty()

    fun mpsSettings(action: Action<MpsSettings>) {
        mpsSettings = MpsSettings(project)
        action.execute(mpsSettings)
    }

    fun ideaSettings(action: Action<IdeaSettings>) {
        ideaSettings = IdeaSettings(project)
        action.execute(ideaSettings)
    }
}

class MpsSettings(val project: Project) {
    val xms: Property<String> = project.objects.property<String>().convention("1024m")
    var xmx: Property<String> = project.objects.property<String>().convention("2048m")
    val ratio: Property<Int> = project.objects.property<Int>().convention(4)
    val disableModelCheckBeforeGeneration: Property<Boolean> = project.objects.property<Boolean>().convention(true)
    val lightTheme: Property<Boolean> = project.objects.property<Boolean>().convention(true)

    val httpPort: Property<Int> = project.objects.property<Int>().convention(63320)

    val debugEnabled: Property<Boolean> = project.objects.property<Boolean>().convention(false)
    val debugPort: Property<Int> = project.objects.property<Int>().convention(5071)
    val debugSuspend: Property<Boolean> = project.objects.property<Boolean>().convention(false)

    val extraVmmArgs: ListProperty<String> = project.objects.listProperty<String>().convention(project.objects.listProperty<String>())
}

class IdeaSettings(val project: Project) {
    val extraIdeaArgs: ListProperty<String> =
        project.objects.listProperty<String>().convention(project.objects.listProperty<String>())
}



