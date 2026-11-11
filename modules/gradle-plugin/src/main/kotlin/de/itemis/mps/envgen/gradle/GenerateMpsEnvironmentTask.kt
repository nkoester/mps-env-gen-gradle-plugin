package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.environment.EnvironmentGenerator
import de.itemis.mps.envgen.environment.EnvironmentPart
import de.itemis.mps.envgen.environment.EnvironmentSpec
import de.itemis.mps.envgen.environment.Theme
import de.itemis.mps.envgen.plugins.PluginDeclaration
import de.itemis.mps.envgen.plugins.PluginInstallationException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

/** writes one environment; configured from an [MpsEnvironment] by the plugin */
@DisableCachingByDefault(because = "Writes machine specific absolute paths; generation is cheap")
abstract class GenerateMpsEnvironmentTask : DefaultTask() {

    /** shared with other environments and prepared MPS distributions, so not an output itself */
    @get:Internal
    abstract val targetPath: DirectoryProperty

    @get:Input
    abstract val environmentName: Property<String>

    @get:OutputDirectory
    val environmentDir: Provider<Directory>
        get() = targetPath.dir(environmentName)

    @get:Input
    abstract val os: ListProperty<OS>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val mpsPath: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    @get:Optional
    abstract val javaHome: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val projectPath: DirectoryProperty

    @get:Input abstract val xms: Property<String>
    @get:Input abstract val xmx: Property<String>
    @get:Input abstract val ratio: Property<Int>
    /** fixed node URL port; unset: the start scripts take the first free one at every start */
    @get:Input @get:Optional abstract val httpPort: Property<Int>
    @get:Input abstract val debug: Property<Boolean>
    @get:Input abstract val debugPort: Property<Int>
    @get:Input abstract val debugSuspend: Property<Boolean>
    @get:Input abstract val theme: Property<Theme>
    @get:Input abstract val modelCheckBeforeGeneration: Property<Boolean>
    @get:Input abstract val updateChecks: Property<Boolean>
    @get:Input abstract val mergeDriverNotification: Property<Boolean>
    @get:Input abstract val selectOpenedFile: Property<Boolean>
    @get:Input abstract val vmOptions: ListProperty<String>
    @get:Input abstract val ideaProperties: ListProperty<String>

    /** plugins to install, merged from the plugin files and the DSL by the plugin */
    @get:Internal
    abstract val plugins: ListProperty<PluginDeclaration>

    /** declarations without the sources, which are tracked by [pluginSources] */
    @get:Input
    val pluginSettings: Provider<List<String>>
        get() = plugins.map { list -> list.map { "${it.name} ${it.source} ${it.systemProperties} ${it.ports} ${it.compatible}" } }

    /** a replaced zip or changed folder installs the plugin again */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val pluginSources: Provider<List<java.io.File>>
        get() = plugins.map { list -> list.map { it.path } }

    /** `--reinstall-plugins`: install all plugins again, also over folders not installed by this plugin */
    @get:Input
    @get:Option(option = "reinstall-plugins", description = "Install all plugins again, also over plugin folders not installed by mps-env-gen.")
    abstract val reinstallPlugins: Property<Boolean>

    /**
     * `--overwrite-settings`: rewrite the user owned files (vmoptions, idea.properties, MPS option files) with the
     * configured values; the previous files are kept in `.backup/<timestamp>`. Without it they are only written if missing.
     */
    @get:Input
    @get:Option(option = "overwrite-settings", description = "Rewrite vmoptions, idea.properties and the MPS option files of the environment with the configured values (backup in .backup/).")
    abstract val overwriteSettings: Property<Boolean>

    /** printed by the start scripts if MPS is missing, e.g. how to prepare it */
    @get:Input
    abstract val missingMpsHint: Property<String>

    init {
        missingMpsHint.convention("Check 'mpsPath' in your Gradle build.")
        overwriteSettings.convention(false)
        reinstallPlugins.convention(false)
        plugins.convention(emptyList())
    }

    @TaskAction
    fun action() = try {
        EnvironmentGenerator(
            lifecycle = logger::lifecycle, info = logger::info, error = logger::error, warn = logger::warn,
            overwriteHint = "run ./gradlew $name --overwrite-settings",
            // no terminal to ask in a build: installed with a warning, which the start scripts repeat
            acceptUncertainPlugin = { true },
        ).generate(
            EnvironmentSpec(
                targetPath = targetPath.get().asFile,
                environmentName = environmentName.get(),
                os = os.get(),
                mpsPath = mpsPath.get().asFile,
                javaHome = javaHome.orNull?.asFile,
                projectPath = projectPath.get().asFile,
                modelCheckBeforeGeneration = modelCheckBeforeGeneration.get(),
                updateChecks = updateChecks.get(),
                mergeDriverNotification = mergeDriverNotification.get(),
                selectOpenedFile = selectOpenedFile.get(),
                theme = theme.get(),
                xms = xms.get(),
                xmx = xmx.get(),
                ratio = ratio.get(),
                httpPort = httpPort.orNull,
                vmOptions = vmOptions.get(),
                debug = debug.get(),
                debugPort = debugPort.get(),
                debugSuspend = debugSuspend.get(),
                ideaProperties = ideaProperties.get(),
                missingMpsHint = missingMpsHint.get(),
                generatorVersion = GenerateMpsEnvironmentTask::class.java.getPackage().implementationVersion ?: "unknown",
                plugins = plugins.get(),
                pluginRestoreHint = "./gradlew $name",
            ),
            overwrite = if (overwriteSettings.get()) EnvironmentPart.USER_OWNED else emptySet(),
            reinstallPlugins = reinstallPlugins.get(),
        )
    } catch (e: PluginInstallationException) {
        throw GradleException(e.message ?: "Plugin installation failed", e)
    }
}
