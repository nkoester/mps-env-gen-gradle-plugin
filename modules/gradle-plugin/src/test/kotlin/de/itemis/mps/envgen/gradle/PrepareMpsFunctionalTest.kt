package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.CACHE_ENV
import de.itemis.mps.envgen.FakeNexus
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.MpsVersion
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.distribution.PreparedDistribution
import de.itemis.mps.envgen.environment.EnvironmentGenerator
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrepareMpsFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @TempDir
    lateinit var repoDir: File

    private lateinit var nexus: FakeNexus
    private val platform = Platform.current

    @TempDir
    lateinit var cacheDir: File

    private fun runner(vararg args: String, dir: File = projectDir): GradleRunner =
        GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args, "--stacktrace")
            // never the real cache folder
            .withEnvironment(System.getenv() + (CACHE_ENV to cacheDir.path))

    private fun buildFile(mpsBlock: String, dir: File = projectDir) {
        File(dir, "settings.gradle.kts").writeText("")
        File(dir, "mps-project/.mps").mkdirs()
        File(dir, "mps-project/.mps/.name").writeText("TestProject")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins { id("de.itemis.mps.mps-env-gen-gradle-plugin") }
            repositories { maven { url = uri("${nexus.url}") } }
            mpsEnvironments {
                projectPath.set(layout.projectDirectory.dir("mps-project"))
            $mpsBlock
            }
            """.trimIndent()
        )
    }

    /** prepared MPS in the shared cache folder (default) */
    private fun distribution(version: String) =
        File(cacheDir, "distributions/" + Platform.distributionDirName(MpsVersion.parse(version), platform.os))

    @BeforeEach
    fun setup() {
        nexus = FakeNexus(repoDir)
    }

    @Test
    fun `prepares MPS from Nexus artifacts and wires it into the environment`() {
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
        buildFile("""    mpsVersion.set("2026.1")
                environments.register("Default")""")

        val result = runner("generateMpsEnvironment-Default").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareMps-2026.1")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateMpsEnvironment-Default")?.outcome)

        val dist = distribution("2026.1")
        val home = File(dist, platform.homePath)
        assertTrue(File(home, platform.scriptLauncher).isFile)
        assertTrue(File(home, "lib/jna/libjnidispatch.so").isFile)
        val java = File(home, "jbr/${platform.javaHome}/${platform.javaExecutable}")
        assertTrue(java.isFile, "$java")
        if (platform.os != OS.WINDOWS) {
            assertTrue(java.canExecute(), "JBR file modes must be kept")
            assertTrue(File(home, "bin/mps.sh").canExecute(), "exec bits must be restored")
        }

        val marker = Properties().apply { File(dist, PreparedDistribution.MARKER_FILE).reader().use { load(it) } }
        assertEquals("2026.1", marker.getProperty("mpsVersion"))
        assertEquals("25.0.3-b329.124", marker.getProperty("jbrVersion"))

        // the environment points at the prepared MPS and its JBR
        val script = File(projectDir, ".mpsconfig/Default/" + EnvironmentGenerator.startScriptName(platform.os, "Default"))
        val scriptText = script.readText()
        assertTrue(home.normalize().path in scriptText, scriptText)
        assertTrue(java.parentFile.parentFile.normalize().path in scriptText, scriptText)

        // second run: nothing is downloaded or unpacked again
        val second = runner("generateMpsEnvironment-Default").build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":prepareMps-2026.1")?.outcome)
    }

    @Test
    fun `keeps settings changed in MPS unless overwritten explicitly`() {
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
        buildFile("""    mpsVersion.set("2026.1")
                environments.register("Default")""")
        runner("generateMpsEnvironment-Default").build()
        val laf = File(projectDir, ".mpsconfig/Default/mps/config/options/laf.xml").apply { writeText("changed in MPS") }

        buildFile("""    mpsVersion.set("2026.1")
                environments.register("Default") { theme.set(de.itemis.mps.envgen.environment.Theme.DARK) }""")
        val kept = runner("generateMpsEnvironment-Default").build()
        assertEquals("changed in MPS", laf.readText())
        assertTrue("theme = DARK" in kept.output && "--overwrite-settings" in kept.output, kept.output)

        runner("generateMpsEnvironment-Default", "--overwrite-settings").build()
        assertTrue("ExperimentalDark" in laf.readText())
        assertTrue(File(projectDir, ".mpsconfig/Default/.backup").listFiles()!!.single().resolve("mps/config/options/laf.xml").isFile)
    }

    @Test
    fun `works with the configuration cache`() {
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
        buildFile("""    mpsVersion.set("2026.1")
                environments.register("Default")""")

        val first = runner("prepareMps-2026.1", "--configuration-cache").build()
        assertTrue("Configuration cache entry stored" in first.output, first.output)
        val second = runner("prepareMps-2026.1", "--configuration-cache").build()
        assertTrue("Configuration cache entry reused" in second.output, second.output)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":prepareMps-2026.1")?.outcome)
    }

    @Test
    fun `environments with the same version share one distribution`() {
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
        buildFile("""    mpsVersion.set("2026.1")
                environments.register("A")
                environments.register("B")""")

        val result = runner("generateMpsEnvironmentAll").build()
        assertEquals(1, result.tasks.count { it.path.startsWith(":prepareMps-") })
        assertTrue(File(projectDir, ".mpsconfig/A").isDirectory && File(projectDir, ".mpsconfig/B").isDirectory)
    }

    @Test
    fun `projects share the prepared MPS in the cache folder`() {
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
        val block = """    mpsVersion.set("2026.1")
                environments.register("Default")"""
        buildFile(block)
        runner("generateMpsEnvironment-Default").build()
        val marker = File(distribution("2026.1"), PreparedDistribution.MARKER_FILE)
        val prepared = marker.lastModified()
        assertFalse(File(projectDir, ".mpsconfig/" + distribution("2026.1").name).exists())

        // another checkout knows nothing of the first one's task history, but must not replace the shared MPS
        val other = File(projectDir, "other").apply { mkdirs() }
        buildFile(block, other)
        val result = runner("generateMpsEnvironment-Default", dir = other).build()
        assertTrue("Using prepared MPS 2026.1" in result.output, result.output)
        assertEquals(prepared, marker.lastModified())
        assertTrue(distribution("2026.1").path in File(other, ".mpsconfig/Default/" + EnvironmentGenerator.startScriptName(platform.os, "Default")).readText())
    }

    @Test
    fun `mpsInTargetPath keeps the prepared MPS in the target path`() {
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
        buildFile("""    mpsVersion.set("2026.1")
                mpsInTargetPath.set(true)
                environments.register("Default")""")
        runner("generateMpsEnvironment-Default").build()
        val local = File(projectDir, ".mpsconfig/" + distribution("2026.1").name)
        assertTrue(File(local, PreparedDistribution.MARKER_FILE).isFile)
        assertFalse(distribution("2026.1").exists())
    }

    @Test
    fun `falls back to another published build of the same line`() {
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.100")
        nexus.jbr("25.0.3-b329.124", platform)
        nexus.jbrVersionListing("21.0.8-b1163.69", "25.0.3-b329.124")
        buildFile("""    mpsVersion.set("2026.1")
                environments.register("Default")""")

        val result = runner("prepareMps-2026.1").build()
        assertTrue("using 25.0.3-b329.124 of the same build line" in result.output, result.output)
        val marker = Properties().apply { File(distribution("2026.1"), PreparedDistribution.MARKER_FILE).reader().use { load(it) } }
        assertEquals("25.0.3-b329.124", marker.getProperty("jbrVersion"))
    }

    @Test
    fun `uses the built-in table for MPS versions without marker`() {
        nexus.mps("2022.2.4")
        nexus.jbr("17.0.6-b469.82", platform)
        buildFile("""    mpsVersion.set("2022.2.4")
                environments.register("Default")""")

        runner("prepareMps-2022.2.4").build()
        assertTrue(File(distribution("2022.2.4"), PreparedDistribution.MARKER_FILE).readText().contains("jbrVersion=17.0.6-b469.82"))
    }

    @Test
    fun `explicit mpsPath takes precedence over mpsVersion`() {
        val local = File(projectDir, "local-mps").apply { mkdirs() }
        buildFile("""    mpsVersion.set("2026.1")
                mpsPath.set(layout.projectDirectory.dir("local-mps"))
                environments.register("Default")""")

        val result = runner("generateMpsEnvironment-Default").build()
        assertFalse(result.tasks.any { it.path.startsWith(":prepareMps-") })
        val script = File(projectDir, ".mpsconfig/Default/" + EnvironmentGenerator.startScriptName(platform.os, "Default"))
        assertTrue(local.path in script.readText())
    }

    @Test
    fun `rejects unsupported MPS 2025_2_1`() {
        nexus.mps("2025.2.1")
        nexus.marker("2025.2.1", "21.0.8-b1038.71")
        buildFile("""    mpsVersion.set("2025.2.1")
                environments.register("Default")""")

        val result = runner("prepareMps-2025.2.1").buildAndFail()
        assertTrue("MPS 2025.2.1 is not supported" in result.output, result.output)
    }

    @Test
    fun `reports missing MPS repository`() {
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
        buildFile("""    mpsVersion.set("2026.1")
                environments.register("Default")""")

        val result = runner("prepareMps-2026.1").buildAndFail()
        assertTrue("Unable to resolve com.jetbrains:mps:2026.1@zip" in result.output, result.output)
    }

    @Test
    fun `rejects environment names reserved for distributions`() {
        buildFile("""    mpsVersion.set("2026.1")
                environments.register("mps-2026.1-LINUX")""")

        val result = runner("tasks").buildAndFail()
        assertTrue("is reserved for prepared MPS distributions" in result.output, result.output)
    }

    @Test
    fun `requires mpsPath or mpsVersion`() {
        buildFile("""    environments.register("Default")""")
        val result = runner("tasks").buildAndFail()
        assertTrue("set either mpsPath or mpsVersion" in result.output, result.output)
    }
}
