package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.FakeNexus
import de.itemis.mps.envgen.FakeNexus.TarEntry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS as JUnitOS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchivesTest {

    @TempDir
    lateinit var tmp: File

    private fun tgz(vararg entries: TarEntry) = File(tmp, "a.tgz").apply { writeBytes(FakeNexus.tgz(entries.toList())) }

    private fun zip(vararg entries: Pair<String, String>) = File(tmp, "a.zip").apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `unzips files and directories`() {
        val out = File(tmp, "out")
        Archives.unzip(zip("bin/mps.sh" to "x", "lib/app.jar" to "jar"), out)
        assertEquals("x", File(out, "bin/mps.sh").readText())
        assertEquals("jar", File(out, "lib/app.jar").readText())
    }

    @Test
    fun `rejects zip entries outside of the target`() {
        assertThrows<IOException> { Archives.unzip(zip("../evil" to "x"), File(tmp, "out")) }
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `untar keeps modes and symlinks`() {
        val out = File(tmp, "out")
        Archives.untarGz(
            tgz(
                TarEntry("jbr/bin/", type = '5', mode = 0b111_101_101),
                TarEntry("jbr/bin/java", "java", mode = 0b111_101_101),
                TarEntry("jbr/release", "v"),
                TarEntry("jbr/lib/libjli.so", type = '2', linkName = "../bin/java"),
            ),
            out,
        )
        assertTrue(File(out, "jbr/bin/java").canExecute())
        assertFalse(File(out, "jbr/release").canExecute())
        val link = File(out, "jbr/lib/libjli.so").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("java", link.toFile().readText())
    }

    @Test
    fun `untar supports GNU long names and PAX paths`() {
        val long = "jbr_jcef-21.0.8-osx-aarch64-b1163.69/Contents/Home/lib/" + "x".repeat(80) + "/libsomething.dylib"
        val longPax = "jbr_jcef-21.0.8-osx-aarch64-b1163.69/Contents/Home/legal/" + "y".repeat(80) + "/LICENSE"
        val out = File(tmp, "out")
        Archives.untarGz(tgz(TarEntry(long, "gnu"), TarEntry(longPax, "pax", pax = true), TarEntry("short", "s")), out)
        assertEquals("gnu", File(out, long).readText())
        assertEquals("pax", File(out, longPax).readText())
        assertEquals("s", File(out, "short").readText())
    }

    @Test
    fun `rejects tar entries outside of the target`() {
        assertThrows<IOException> { Archives.untarGz(tgz(TarEntry("../evil", "x")), File(tmp, "out")) }
    }
}
