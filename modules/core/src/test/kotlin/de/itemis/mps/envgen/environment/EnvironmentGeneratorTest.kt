package de.itemis.mps.envgen.environment

import de.itemis.mps.envgen.OS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentGeneratorTest {

    @TempDir
    lateinit var tmp: File

    private fun spec(project: File? = null) = EnvironmentSpec(
        targetPath = File(tmp, ".mpsconfig"),
        environmentName = "Default",
        os = listOf(OS.LINUX, OS.WINDOWS),
        mpsPath = File(tmp, "mps").apply { mkdirs() },
        projectPath = project,
        missingMpsHint = "hint",
        generatorVersion = "1.2.3",
    )

    @Test
    fun `generates without project`() {
        val spec = spec()
        EnvironmentGenerator().generate(spec)
        val options = File(spec.mpsConfigDir, "config/options")
        assertTrue(File(options, "laf.xml").isFile)
        assertFalse(File(options, "recentProjects.xml").exists())
        assertFalse(File(options, "trusted-paths.xml").exists())
        assertTrue(File(spec.environmentDir, "startMPS_LINUX_Default.sh").isFile)
        assertTrue(File(spec.environmentDir, "startMPS_WINDOWS_Default.bat").isFile)
        assertTrue("version: 1.2.3" in File(spec.mpsConfigDir, "idea.properties").readText())
    }

    @Test
    fun `refreshes only the start scripts`() {
        val spec = spec(project = File(tmp, "project").apply { mkdirs() })
        EnvironmentGenerator().generate(spec)
        val laf = File(spec.mpsConfigDir, "config/options/laf.xml").apply { writeText("user change") }
        val script = File(spec.environmentDir, "startMPS_LINUX_Default.sh").apply { writeText("old") }

        EnvironmentGenerator().refreshStartScripts(spec.copy(missingMpsHint = "new hint"))
        assertEquals("user change", laf.readText())
        assertTrue("new hint" in script.readText())
    }

    @Test
    fun `refresh requires an existing environment`() {
        assertThrows<IllegalArgumentException> { EnvironmentGenerator().refreshStartScripts(spec()) }
    }

    @Test
    fun `script names per OS`() {
        assertEquals("startMPS_MAC_x.sh", EnvironmentGenerator.startScriptName(OS.MAC, "x"))
        assertEquals("startMPS_WINDOWS_x.bat", EnvironmentGenerator.startScriptName(OS.WINDOWS, "x"))
    }
}
