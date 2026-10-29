package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.FakeNexus
import de.itemis.mps.envgen.distribution.Platform
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** supported range: Gradle 8.14 (Java 17) up to the current version, Kotlin and Groovy build scripts */
class CompatibilityTest {

    @TempDir
    lateinit var projectDir: File

    @TempDir
    lateinit var repoDir: File

    companion object {
        const val MINIMUM_GRADLE = "8.14.3"

        @JvmStatic
        fun setups(): List<Arguments> = listOf(MINIMUM_GRADLE, GradleVersion.current().version).flatMap { gradle ->
            listOf(Arguments.of(gradle, "kotlin"), Arguments.of(gradle, "groovy"))
        }
    }

    private fun kotlinScript(repo: String) = """
        import de.itemis.mps.envgen.OS
        import de.itemis.mps.envgen.environment.Theme

        plugins { id("de.itemis.mps.mps-env-gen-gradle-plugin") }
        repositories { maven { url = uri("$repo") } }

        mpsEnvironments {
            mpsVersion.set("2026.1")
            projectPath.set(layout.projectDirectory.dir("mps-project"))
            environments {
                register("dev") {
                    os.set(listOf(OS.current))
                    xmx.set("4096m")
                    theme.set(Theme.DARK)
                    debug.set(true)
                    vmOptions.add("-Dcompat=kotlin")
                }
            }
        }
    """.trimIndent()

    private fun groovyScript(repo: String) = """
        import de.itemis.mps.envgen.OS
        import de.itemis.mps.envgen.environment.Theme

        plugins { id 'de.itemis.mps.mps-env-gen-gradle-plugin' }
        repositories { maven { url = uri('$repo') } }

        mpsEnvironments {
            mpsVersion = '2026.1'
            projectPath = layout.projectDirectory.dir('mps-project')
            environments {
                register('dev') {
                    os = [OS.current]
                    xmx = '4096m'
                    theme = Theme.DARK
                    debug = true
                    vmOptions.add('-Dcompat=groovy')
                }
            }
        }
    """.trimIndent()

    @ParameterizedTest(name = "Gradle {0}, {1} DSL")
    @MethodSource("setups")
    fun `prepares MPS and generates an environment`(gradle: String, dsl: String) {
        val nexus = FakeNexus(repoDir)
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", Platform.current)

        File(projectDir, "mps-project/.mps").mkdirs()
        val settings = if (dsl == "kotlin") "settings.gradle.kts" else "settings.gradle"
        File(projectDir, settings).writeText("")
        File(projectDir, if (dsl == "kotlin") "build.gradle.kts" else "build.gradle")
            .writeText(if (dsl == "kotlin") kotlinScript(nexus.url) else groovyScript(nexus.url))

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradle)
            .withArguments("generateMpsEnvironment-Dev", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareMps-2026.1")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateMpsEnvironment-Dev")?.outcome)
        val vmoptions = File(projectDir, ".mpsconfig/dev/mps/mps64.vmoptions").readText()
        assertTrue("-Xmx4096m" in vmoptions && "-Dcompat=$dsl" in vmoptions && "address=127.0.0.1:5071" in vmoptions, vmoptions)
        // MPS 2026.1 has the new UI
        assertTrue("ExperimentalDark" in File(projectDir, ".mpsconfig/dev/mps/config/options/laf.xml").readText())
    }
}
