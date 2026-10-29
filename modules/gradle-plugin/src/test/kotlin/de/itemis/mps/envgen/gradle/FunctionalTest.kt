package de.itemis.mps.envgen.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunctionalTest {

    // vars
    @field:TempDir
    lateinit var testProjectDir: File
    private lateinit var buildFile: File
    private lateinit var settingsFile: File
    private lateinit var mpsTestProjectPath: File

    // helpers
    private fun gradleRunner(): GradleRunner =
        GradleRunner.create().withProjectDir(testProjectDir).withPluginClasspath()

    private fun extractTestProject(to: File) {
        val testProjectUrl = this.javaClass.classLoader.getResource("test-project")
        val path = Paths.get(testProjectUrl!!.toURI())
        val files = Files.walk(path).filter { Files.isRegularFile(it) }.map { it.toFile() }.toList()
        files.forEach {
            val rel = path.relativize(it.toPath())
            it.copyTo(to.toPath().resolve(rel).toFile())
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // test preperation
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @BeforeEach
    fun setup() {

        buildFile = File(testProjectDir, "build.gradle.kts")
        buildFile.writeText(buildScriptBoilerplate("2022.2.4"))

        settingsFile = File(testProjectDir, "settings.gradle.kts")
//        settingsFile.writeText(settingsScriptBoilerplate())

        Path(testProjectDir.toString(), "build/mps").createDirectories()
        mpsTestProjectPath = File(testProjectDir, "mps-project")
        extractTestProject(mpsTestProjectPath)
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // actual tests
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Test
    fun `writes no environment `() {
        // run the default task
        val result = gradleRunner().withArguments(":generateMpsEnvironmentAll").build()

        // check if task configuration actually runs
        assertEquals(TaskOutcome.UP_TO_DATE, result.task(":generateMpsEnvironmentAll")?.outcome)

        // test for expected output
    }

    @Test
    fun `writes valid default environment`() {
        addDefaultEnvironmentBlock(buildFile)

        // run the default task
        val result = gradleRunner().withArguments(":generateMpsEnvironment-Default").build()

        // check if task configuration actually runs
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateMpsEnvironment-Default")?.outcome)

        // test for expected output
        assertTrue(File(testProjectDir, ".mpsconfig/Default").exists())
        assertTrue(File(testProjectDir, ".mpsconfig/Default/mps/idea.properties").exists())
        assertTrue(File(testProjectDir, ".mpsconfig/Default/mps/mps64.vmoptions").exists())
    }

    @Test
    fun `writes multiple environments`() {
        addMultipleEnvironments(buildFile)

        // run the default task
        val result = gradleRunner().withArguments(":generateMpsEnvironmentAll").build()

        // check if task configuration actually runs
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateMpsEnvironmentAll")?.outcome)

        // test for expected output
        assertTrue(File(testProjectDir, ".mpsconfig/Default").exists())
        assertTrue(File(testProjectDir, ".mpsconfig/Default/mps/idea.properties").exists())
        assertTrue(File(testProjectDir, ".mpsconfig/Default/mps/mps64.vmoptions").exists())

        assertTrue(File(testProjectDir, ".mpsconfig/Second").exists())
        assertTrue(File(testProjectDir, ".mpsconfig/Second/mps/idea.properties").exists())
        assertTrue(File(testProjectDir, ".mpsconfig/Second/mps/mps64.vmoptions").exists())

        assertTrue(File(testProjectDir, ".mpsconfig/Third").exists())
        assertTrue(File(testProjectDir, ".mpsconfig/Third/mps/idea.properties").exists())
        assertTrue(File(testProjectDir, ".mpsconfig/Third/mps/mps64.vmoptions").exists())
    }


    @Test
    fun `can set java home`() {
        // todo actually create the jbr folder
        val jbrFolder = File(testProjectDir, "build/jbr")
        jbrFolder.mkdirs()

        addDEnvironmentBlockWithJavaHome(buildFile)

        // run the default task
        val result = gradleRunner().withArguments(":generateMpsEnvironmentAll").build()

        // check if task configuration actually runs
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateMpsEnvironmentAll")?.outcome)
        assertTrue(File(testProjectDir, ".mpsconfig/Default/startMPS_LINUX_Default.sh").exists())
//        assertFalse(File(testProjectDir, ".mpsconfig/Default/startMPS_LINUX_Default.sh").readText().contains("JAVA_HOME_CANDIDATE=\"REPLACE_ME__JAVA_HOME\""))

        //TODO: Check the generated files if the java home was set
    }

    @Test
    fun `writes configured settings into generated files`() {
        val otherProject = File(testProjectDir, "other-project").apply { mkdirs() }
        buildFile.appendText(
            """
                    mpsEnvironments {
                        mpsPath.set(Path(myMpsPath.toString()).toFile())
                        projectPath.set(Path("${mpsTestProjectPath.path}").toFile())

                        environments.register("Default") {
                            os.set(listOf(OS.LINUX, OS.MAC))
                            projectPath.set(Path("${otherProject.path}").toFile())
                            httpPort.set(9292)
                        }
                    }
                """.trimIndent()
        )

        val result = gradleRunner().withArguments(":generateMpsEnvironment-Default").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateMpsEnvironment-Default")?.outcome)

        val envDir = File(testProjectDir, ".mpsconfig/Default")
        assertTrue(File(envDir, "mps/mps64.vmoptions").readText().contains("-Dide.httpsupport.internalPort=9292"))
        assertTrue(File(envDir, "README.md").readText().contains(" - LINUX\n - MAC\n"))

        // per-environment project path wins; title falls back to the folder name without .mps/.name
        val recentProjects = File(envDir, "mps/config/options/recentProjects.xml").readText()
        assertTrue(recentProjects.contains("key=\"${otherProject.path}\""))
        assertTrue(recentProjects.contains("frameTitle=\"other-project\""))
    }

    // test TODOs:
    // - validate file content based on the given options (parameter round trip)
    // - validate generated default parameter values
    // - start MPS with a generated script

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // build file boilerplate
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private fun addDefaultEnvironmentBlock(buildFile: File) {
        buildFile.appendText(
            """
                    mpsEnvironments {
                        mpsPath.set(Path(myMpsPath.toString()).toFile())
                        projectPath.set(Path("${mpsTestProjectPath.path}").toFile())
                        
                        environments.register("Default")
                    }
                """.trimIndent()
        )
    }

    private fun addDEnvironmentBlockWithJavaHome(buildFile: File) {
        buildFile.appendText(
            """
                    mpsEnvironments {
                        mpsPath.set(Path(myMpsPath.toString()).toFile())
                        javaHome.set(Path(myJavaHome.toString()).toFile())
                        projectPath.set(Path("${mpsTestProjectPath.path}").toFile())
                        
                        environments.register("Default")
                    }
                """.trimIndent()
        )
    }

    private fun addMultipleEnvironments(buildFile: File) {
        buildFile.appendText(
            """
                    mpsEnvironments {
                        mpsPath.set(Path(myMpsPath.toString()).toFile())
                        projectPath.set(Path("${mpsTestProjectPath.path}").toFile())
                        
                        environments.register("Default")
                        environments.register("Second")
                        environments.register("Third")
                    }
                """.trimIndent()
        )
    }

    private fun settingsScriptBoilerplate() = """
//        plugins {
//          ...
//        }
    """.trimIndent()

    private fun buildScriptBoilerplate(mpsVersion: String) = """
        import de.itemis.mps.envgen.OS
        import kotlin.io.path.Path

        plugins {
            id("de.itemis.mps.mps-env-gen-gradle-plugin")
        }


        val myMpsPath = Path(project.layout.buildDirectory.get().toString(), "mps")
        val myDependencyPath = Path(project.layout.buildDirectory.get().toString(), "dependencies")
        val myJavaHome = Path(project.layout.buildDirectory.get().toString(), "jbr")

        val mps: Configuration = configurations.create("mps")
        val mpsExtensions: Configuration = configurations.create("mpsExtensions")

        dependencies {
            mps("com.jetbrains:mps:${mpsVersion}")
            mpsExtensions("de.itemis.mps:extensions:2022.2.2988.736f389")
        }

        val extractMps = tasks.register<Copy>("extractMps") {
            from({ mps.resolve().map { zipTree(it) } })
            into(myMpsPath)
        }
        val extractMPS_extensions = tasks.register<Copy>("extractMPS_extensions") {
            from({ mpsExtensions.resolve().map { zipTree(it) } })
            into(myDependencyPath)
        }

        val setup = tasks.register("setup") {
            group = "Setup"
            description = "Download and extract MPS and the all MPS dependencies."
            dependsOn(extractMps)
            dependsOn(extractMPS_extensions)
        }
               
    """.trimIndent() + "\n"
}