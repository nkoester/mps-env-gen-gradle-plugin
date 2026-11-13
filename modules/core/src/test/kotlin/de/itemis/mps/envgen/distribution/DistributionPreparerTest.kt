package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DistributionPreparerTest {

    @TempDir
    lateinit var tmp: File

    private val platform = Platform.of(OS.LINUX, Arch.AMD64)
    private val mps = MpsVersion.parse("2026.1")
    private val jbr = JbrVersion.parse("25.0.3-b329.124")

    private fun write(root: File, vararg paths: String) =
        paths.forEach { File(root, it).apply { parentFile.mkdirs() }.writeText(it) }

    private fun prepareMps(target: File, mpsFiles: Array<String> = arrayOf("bin/mps.sh", "bin/linux/fsnotifier")) =
        DistributionPreparer.prepareMps(target, platform, mps, jbr, unpackMps = { write(it, *mpsFiles) })

    private fun prepareJbr(target: File, javaPath: String = "jbr_jcef-25.0.3-linux-x64-b329.124/bin/java") =
        DistributionPreparer.prepareJbr(target, platform, jbr, unpackJbr = { write(it, javaPath) })

    private fun marker(root: File) = Properties().apply { File(root, PreparedDistribution.MARKER_FILE).reader().use { load(it) } }

    @Test
    fun `assembles MPS without JBR and records its default JBR`() {
        val target = File(tmp, "mps-2026.1-LINUX")
        val prepared = prepareMps(target)

        assertTrue(File(target, "bin/fsnotifier").isFile)
        assertFalse(File(target, "jbr").exists(), "the JBR has a folder of its own")
        assertEquals(Launcher(Launcher.Kind.SCRIPT, "bin/mps.sh"), prepared.launcher)
        assertEquals("2026.1", marker(target).getProperty("mpsVersion"))
        assertEquals("25.0.3-b329.124", marker(target).getProperty("jbrVersion"))
        assertEquals(prepared, DistributionPreparer.read(target)?.copy(warnings = prepared.warnings))
        assertNull(DistributionPreparer.readJbr(target))
    }

    @Test
    fun `prepares the JBR in a folder of its own`() {
        val target = File(tmp, Platform.jbrDirName(jbr, platform))
        val prepared = prepareJbr(target)

        assertEquals("jbr-25.0.3-b329.124-linux-x64", target.name)
        assertTrue(File(target, "bin/java").isFile, "top folder of the JBR must be stripped")
        assertEquals(target.normalize(), prepared.javaHome)
        assertEquals(prepared, DistributionPreparer.readJbr(target))
        assertNull(DistributionPreparer.read(target))
    }

    @Test
    fun `replaces an incomplete folder`() {
        val target = File(tmp, "mps-2026.1-LINUX")
        write(target, "stale.txt")
        prepareMps(target)
        assertFalse(File(target, "stale.txt").exists())
    }

    @Test
    fun `never replaces a prepared folder`() {
        val target = File(tmp, "mps-2026.1-LINUX")
        prepareMps(target)
        write(target, "in-use.txt")

        val reused = DistributionPreparer.prepareMpsOrReuse(target, platform, mps, { error("not resolved") }, { error("not fetched") })
        assertEquals(mps, reused.mpsVersion)
        assertTrue(File(target, "in-use.txt").isFile)

        val other = Platform.of(OS.LINUX, Arch.AARCH64)
        val e = assertThrows<DistributionPreparationException> {
            DistributionPreparer.prepareMpsOrReuse(target, other, mps, { jbr }, { error("not fetched") })
        }
        assertTrue("Remove it or use another cache folder" in e.message!!)
        assertTrue(File(target, "in-use.txt").isFile)
        assertThrows<DistributionPreparationException> { prepareMps(target) }
    }

    @Test
    fun `reuses a prepared JBR, also when the version is written differently`() {
        val target = File(tmp, Platform.jbrDirName(jbr, platform))
        prepareJbr(target)
        val reused = DistributionPreparer.prepareJbrOrReuse(target, platform, JbrVersion.parse("25.0.3b329.124")) { error("not fetched") }
        assertEquals(jbr, reused.version)
    }

    @Test
    fun `reuses a prepared MPS that contains a JBR`() {
        val target = File(tmp, "mps-2025.1.4-LINUX")
        write(target, "bin/mps.sh", "jbr/bin/java")
        File(target, PreparedDistribution.MARKER_FILE).writeText(
            "mpsVersion=2025.1.4\njbrVersion=21.0.8-b1038.71\nos=LINUX\narch=AMD64\nhome=.\nlauncher.kind=SCRIPT\nlauncher.path=bin/mps.sh\n"
        )
        val prepared = DistributionPreparer.read(target)!!
        assertEquals(JbrVersion.parse("21.0.8-b1038.71"), prepared.jbrVersion, "the default JBR of later environments")
        assertTrue(DistributionPreparer.isPrepared(File(target, "jbr")))
    }

    @Test
    fun `tells a prepared JBR from a Java home of the user`() {
        val prepared = prepareJbr(File(tmp, Platform.jbrDirName(jbr, platform)))
        assertTrue(DistributionPreparer.isPrepared(prepared.javaHome))
        // removed with the cache: recognised by its name
        assertTrue(DistributionPreparer.isPrepared(File(tmp, "gone/jbr-21.0.8-b1163.69-osx-aarch64/Contents/Home")))
        assertFalse(DistributionPreparer.isPrepared(File(tmp, "opt/jdk-21")))
    }

    @Test
    fun `fails if the result is not runnable`() {
        val e = assertThrows<DistributionPreparationException> { prepareMps(File(tmp, "x"), mpsFiles = arrayOf("lib/app.jar")) }
        assertTrue("bin/mps.sh is missing" in e.message!!)
        val j = assertThrows<DistributionPreparationException> { prepareJbr(File(tmp, "j"), javaPath = "jbr/lib/x.so") }
        assertTrue("bin/java is missing" in j.message!!)
    }

    @Test
    fun `mac distribution lives in an app bundle, its JBR in Contents Home`() {
        val mac = Platform.of(OS.MAC, Arch.AARCH64)
        val mpsTarget = File(tmp, "mps-2026.1-MAC")
        val prepared = DistributionPreparer.prepareMps(mpsTarget, mac, mps, jbr, unpackMps = { write(it, "bin/mps.sh") })
        assertEquals(File(mpsTarget, "MPS.app/Contents"), prepared.home)

        val jbrTarget = File(tmp, Platform.jbrDirName(jbr, mac))
        val macJbr = DistributionPreparer.prepareJbr(jbrTarget, mac, jbr) { write(it, "jbr_jcef-25.0.3-osx-aarch64-b329.124/Contents/Home/bin/java") }
        assertEquals("jbr-25.0.3-b329.124-osx-aarch64", jbrTarget.name)
        assertTrue(File(macJbr.javaHome, "bin/java").isFile)
    }
}
