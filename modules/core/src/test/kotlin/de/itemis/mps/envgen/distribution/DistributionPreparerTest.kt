package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistributionPreparerTest {

    @TempDir
    lateinit var tmp: File

    private val platform = Platform.of(OS.LINUX, Arch.AMD64)
    private val mps = MpsVersion.parse("2026.1")
    private val jbr = JbrVersion.parse("25.0.3-b329.124")

    private fun write(root: File, vararg paths: String) =
        paths.forEach { File(root, it).apply { parentFile.mkdirs() }.writeText(it) }

    private fun prepare(target: File, mpsFiles: Array<String> = arrayOf("bin/mps.sh", "bin/linux/fsnotifier")) =
        DistributionPreparer.prepare(
            target, platform, mps, jbr,
            unpackMps = { write(it, *mpsFiles) },
            unpackJbr = { write(it, "jbr_jcef-25.0.3-linux-x64-b329.124/bin/java") },
        )

    @Test
    fun `assembles distribution and writes marker`() {
        val target = File(tmp, "mps-2026.1-LINUX")
        val prepared = prepare(target)

        assertTrue(File(target, "bin/fsnotifier").isFile)
        assertTrue(File(target, "jbr/bin/java").isFile, "top folder of the JBR must be stripped")
        assertEquals(File(target, "jbr").normalize(), prepared.javaHome)
        assertEquals(Launcher(Launcher.Kind.SCRIPT, "bin/mps.sh"), prepared.launcher)

        val marker = Properties().apply { File(target, PreparedDistribution.MARKER_FILE).reader().use { load(it) } }
        assertEquals("2026.1", marker.getProperty("mpsVersion"))
        assertEquals("25.0.3-b329.124", marker.getProperty("jbrVersion"))
        assertEquals("SCRIPT", marker.getProperty("launcher.kind"))
    }

    @Test
    fun `replaces a previous distribution completely`() {
        val target = File(tmp, "mps-2026.1-LINUX")
        write(target, "stale.txt")
        prepare(target)
        assertFalse(File(target, "stale.txt").exists())
    }

    @Test
    fun `fails if the result is not runnable`() {
        val e = assertThrows<DistributionPreparationException> { prepare(File(tmp, "x"), mpsFiles = arrayOf("lib/app.jar")) }
        assertTrue("bin/mps.sh is missing" in e.message!!)
    }

    @Test
    fun `mac distribution lives in an app bundle`() {
        val mac = Platform.of(OS.MAC, Arch.AARCH64)
        val target = File(tmp, "mps-2026.1-MAC")
        val prepared = DistributionPreparer.prepare(
            target, mac, mps, jbr,
            unpackMps = { write(it, "bin/mps.sh") },
            unpackJbr = { write(it, "jbr_jcef-25.0.3-osx-aarch64-b329.124/Contents/Home/bin/java") },
        )
        assertEquals(File(target, "MPS.app/Contents"), prepared.home)
        assertTrue(File(prepared.javaHome, "bin/java").isFile)
    }
}
