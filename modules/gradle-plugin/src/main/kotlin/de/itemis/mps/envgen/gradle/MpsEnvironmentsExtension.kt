package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.environment.Theme
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * `mpsEnvironments { }`: defaults for all environments plus the environments themselves.
 *
 * ```
 * mpsEnvironments {
 *     mpsVersion.set("2025.3")
 *     projectPath.set(layout.projectDirectory.dir("mps-project"))
 *     environments {
 *         register("default")
 *         register("debug") { debug.set(true); xmx.set("4096m") }
 *     }
 * }
 * ```
 */
abstract class MpsEnvironmentsExtension @Inject constructor(objects: ObjectFactory) {

    /** explicit MPS installation; takes precedence over [mpsVersion] */
    abstract val mpsPath: DirectoryProperty

    /** MPS version to download from Nexus and prepare as runnable MPS */
    abstract val mpsVersion: Property<String>

    /** JBR for a prepared MPS; default: derived from the MPS version */
    abstract val jbrVersion: Property<String>

    /** JBR/JDK to run MPS with; default: the JBR of a prepared MPS */
    abstract val javaHome: DirectoryProperty

    /** MPS project to trust and open */
    abstract val projectPath: DirectoryProperty

    /** folder of the environments and prepared distributions; default `.mpsconfig` */
    abstract val targetPath: DirectoryProperty

    val environments: NamedDomainObjectContainer<MpsEnvironment> = objects.domainObjectContainer(MpsEnvironment::class.java)

    fun environments(action: Action<NamedDomainObjectContainer<MpsEnvironment>>) = action.execute(environments)
}

/** one environment; unset values fall back to the defaults of [MpsEnvironmentsExtension] */
abstract class MpsEnvironment @Inject constructor(private val name: String) : Named {

    override fun getName(): String = name

    /** overrides the default MPS; an explicit path takes precedence over a version */
    abstract val mpsPath: DirectoryProperty
    abstract val mpsVersion: Property<String>
    abstract val jbrVersion: Property<String>
    abstract val javaHome: DirectoryProperty
    abstract val projectPath: DirectoryProperty

    /** start scripts to generate; default: the current OS */
    abstract val os: ListProperty<OS>

    abstract val xms: Property<String>
    abstract val xmx: Property<String>
    abstract val ratio: Property<Int>
    abstract val httpPort: Property<Int>
    abstract val debug: Property<Boolean>
    abstract val debugPort: Property<Int>
    abstract val debugSuspend: Property<Boolean>
    abstract val theme: Property<Theme>
    abstract val modelCheckBeforeGeneration: Property<Boolean>

    /** additional lines of the vmoptions file */
    abstract val vmOptions: ListProperty<String>

    /** additional lines of idea.properties */
    abstract val ideaProperties: ListProperty<String>
}
