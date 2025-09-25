package de.itemis.mps

import org.gradle.api.file.Directory
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory

class FunctionalTest {

    @field:TempDir
    lateinit var testProjectDir: File

    private lateinit var buildFile: File
    private lateinit var mpsTestProjectPath: File

    @BeforeEach
    fun setup() {

        buildFile = File(testProjectDir, "build.gradle.kts")

        val settingsFile = File(testProjectDir, "settings.gradle.kts")
        settingsFile.writeText(settingsScriptBoilerplate())

        Path(testProjectDir.toString(),"build/mps").createDirectories()

        mpsTestProjectPath = File(testProjectDir, "mps-project")

        extractTestProject(mpsTestProjectPath)
    }

    fun extractTestProject(to: File) {
        val url = this.javaClass.classLoader.getResource("test-project")
        val path = Paths.get(url.toURI())
        val files = Files.walk(path).filter { Files.isRegularFile(it) }.map { it.toFile() }.toList()
        files.forEach {
            val rel = path.relativize(it.toPath())
            it.copyTo(to.toPath().resolve(rel).toFile())
        }
    }

    private fun settingsScriptBoilerplate() = """
//        plugins {
//          ...
//        }
    """.trimIndent()

    private fun buildScriptBoilerplate(mpsVersion: String) = """
        import de.itemis.mps.Utils
        import kotlin.io.path.Path

        plugins {
            id("de.itemis.mps.mps-env-gen-gradle-plugin")
        }

        repositories {
            // itemis nexus
            maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
            mavenLocal()
        }

        val myMpsPath = Path(project.layout.buildDirectory.get().toString(), "mps")
        val myDependencyPath = Path(project.layout.buildDirectory.get().toString(), "dependencies")

        val mps: Configuration by configurations.creating
        val mpsExtensions: Configuration by configurations.creating

        dependencies {
            mps("com.jetbrains:mps:2022.2.4")
            mpsExtensions("de.itemis.mps:extensions:2022.2.2988.736f389")
        }

        val extractMps by tasks.registering(Copy::class) {
            from({ mps.resolve().map { zipTree(it) } })
            into(myMpsPath)
        }
        val extractMPS_extensions by tasks.registering(Copy::class) {
            from({ mpsExtensions.resolve().map { zipTree(it) } })
            into(myDependencyPath)
        }

        val setup by tasks.registering {
            group = "Setup"
            description = "Download and extract MPS and the all MPS dependencies."
            dependsOn(extractMps)
            dependsOn(extractMPS_extensions)
        }
               
    """.trimIndent() + "\n"

    @Test
    fun `writes default environment`() {
        buildFile.writeText(
            buildScriptBoilerplate("2025.1") + """
            mpsEnvironments {
                mpsPath.set(Path(myMpsPath.toString()).toFile())
                mpsProjectPath.set(Path("${mpsTestProjectPath.path}").toFile())
                
                environment("Default"){}
            }
        """.trimIndent()
        )

        println(buildFile.readText())
        val result = gradleRunner().withArguments(":generateMpsEnvironmentDefault").build()
        result.output

        // check if task configuration actually runs
        Assertions.assertTrue {
            result.output.toString().contains("Configuring new task(s) for environment(s) [Default]")
        }

        val outcome = result.task(":setup :generateMpsEnvironmentDefault")?.outcome


        println(outcome)
    }


    private fun gradleRunner(): GradleRunner = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withPluginClasspath()
}