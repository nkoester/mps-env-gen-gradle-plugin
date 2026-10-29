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
 *     mpsVersion.set("2025.1.4")
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

    /** folder of the environments; default `.mpsconfig` */
    abstract val targetPath: DirectoryProperty

    /**
     * prepare MPS from Nexus in [targetPath] instead of the shared cache folder (`~/.cache/mps-env-gen/distributions`
     * on Linux, `MPS_ENV_GEN_CACHE` overrides it); default: false
     */
    abstract val mpsInTargetPath: Property<Boolean>

    val environments: NamedDomainObjectContainer<MpsEnvironment> = objects.domainObjectContainer(MpsEnvironment::class.java)

    fun environments(action: Action<NamedDomainObjectContainer<MpsEnvironment>>) = action.execute(environments)
}

/** one environment; unset values fall back to the defaults of [MpsEnvironmentsExtension] */
abstract class MpsEnvironment @Inject constructor(private val name: String) : Named {

    override fun getName(): String = name

    /** installed MPS; overrides the default and takes precedence over a version */
    abstract val mpsPath: DirectoryProperty

    /** MPS version to download from Nexus; overrides the default */
    abstract val mpsVersion: Property<String>

    /** JBR for a prepared MPS; default: derived from the MPS version */
    abstract val jbrVersion: Property<String>

    /** JBR/JDK to run MPS with; default: the JBR of a prepared MPS */
    abstract val javaHome: DirectoryProperty

    /** MPS project to trust and open on first start; overrides the default */
    abstract val projectPath: DirectoryProperty

    /** start scripts to generate; default: the current OS */
    abstract val os: ListProperty<OS>

    /** initial heap (`-Xms`); default: 1024m */
    abstract val xms: Property<String>

    /** maximum heap (`-Xmx`); default: 2048m */
    abstract val xmx: Property<String>

    /** `-XX:NewRatio`; default: 4 */
    abstract val ratio: Property<Int>

    /** fixed node URL port, MPS does not start if it is in use; unset: the first free one from 63321 at every start */
    abstract val httpPort: Property<Int>

    /** remote debugging on every start; default: false (the start script's `-d` enables it for one start) */
    abstract val debug: Property<Boolean>

    /** first debug port tried, the next free one up to +20 is taken; default: 5071 */
    abstract val debugPort: Property<Int>

    /** MPS waits for a debugger; default: false */
    abstract val debugSuspend: Property<Boolean>

    /** UI theme of a new environment; default: [Theme.LIGHT] */
    abstract val theme: Property<Theme>

    /** MPS checks models before generation; default: false */
    abstract val modelCheckBeforeGeneration: Property<Boolean>

    /** additional lines of the vmoptions file */
    abstract val vmOptions: ListProperty<String>

    /** additional lines of idea.properties */
    abstract val ideaProperties: ListProperty<String>
}
