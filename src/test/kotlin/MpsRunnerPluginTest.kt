package test.codeexecution

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

import org.gradle.api.GradleException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.streams.toList

// TODO
// TODO
// TODO
// TODO

class MpsExecuteTaskTest {
    @Rule
    @JvmField
    val testProjectDir: TemporaryFolder = TemporaryFolder()

    private lateinit var buildFile: File
    private lateinit var mpsTestProjectPath: File

    @Before
    fun setUp() {
        buildFile = testProjectDir.newFile("build.gradle.kts")

        val settingsFile = testProjectDir.newFile("settings.gradle.kts")
        settingsFile.writeText(settingsScriptBoilerplate())

        mpsTestProjectPath = testProjectDir.newFolder("mps-prj")
        extractTestProject("test-project", mpsTestProjectPath)
    }

    private fun settingsScriptBoilerplate() = """
        plugins {
            id("org.gradle.toolchains.foojay-resolver-convention") version ("0.7.0")
        }
    """.trimIndent()

    private fun buildScriptBoilerplate(mpsVersion: String) = """
        import de.itemis.mps.gradle.tasks.MpsExecute
        import de.itemis.mps.gradle.tasks.MpsGenerate

        plugins {
            id("de.itemis.mps.gradle.common")
        }
        
        repositories {
            mavenCentral()
            maven("https://artifacts.itemis.cloud/repository/maven-mps")
        }
        
        val mps = configurations.create("mps")
        
        dependencies {
            mps("com.jetbrains:mps:$mpsVersion")
        }
        
        val resolveMps by tasks.registering(Sync::class) {
            from({ zipTree(mps.singleFile) })
            into(layout.buildDirectory.dir("mps"))
        }
        
        val resolvedMpsHome = resolveMps.map { it.destinationDir }

        val generate by tasks.registering(MpsGenerate::class) {
            mpsHome.set(layout.dir(resolvedMpsHome))
            projectLocation.set(file("${mpsTestProjectPath.canonicalPath}"))

            doFirst {
                println(resolvedMpsHome.get().listFiles()?.toList())
            }
        }
        
        val execute by tasks.registering(MpsExecute::class) {
            dependsOn(generate)
            mpsHome.set(layout.dir(resolvedMpsHome))
            projectLocation.set(file("${mpsTestProjectPath.canonicalPath}"))

            doFirst {
                println(resolvedMpsHome.get())
            }
        }
    """.trimIndent() + "\n"

    @Test
    fun `execute with Project`() {
        buildFile.writeText(buildScriptBoilerplate("2021.3.4") + """
            execute {
                module.set("NewSolution")
                className.set("NewSolution.myModel.MyClass")
                method.set("onlyProject")
            }
        """.trimIndent())

        val result = gradleRunner().withArguments("execute").build()

        Assert.assertEquals(TaskOutcome.SUCCESS, result.task(":execute")?.outcome)
    }

    @Test
    fun `execute with Project and args`() {
        buildFile.writeText(buildScriptBoilerplate("2021.3.4") + """
            execute {
                module.set("NewSolution")
                className.set("NewSolution.myModel.MyClass")
                method.set("projectAndArgs")

                methodArguments.set(listOf("arg1", "arg2"))
            }
        """.trimIndent())

        val result = gradleRunner().withArguments("execute").build()

        Assert.assertEquals(TaskOutcome.SUCCESS, result.task(":execute")?.outcome)
    }

    private fun gradleRunner(): GradleRunner = GradleRunner.create()
        .withProjectDir(testProjectDir.root)
        .withPluginClasspath()

    fun extractTestProject(projectName: String, to: File) {
        val url = MpsExecuteTaskTest::class.java.classLoader.getResource(projectName) ?: throw GradleException("Project not found on class path: $projectName")
        val path = Paths.get(url.toURI())
        val files = Files.walk(path).filter { Files.isRegularFile(it) }.map { it.toFile() }.toList()
        files.forEach {
            val rel = path.relativize(it.toPath())
            it.copyTo(to.toPath().resolve(rel).toFile())
        }
    }
}