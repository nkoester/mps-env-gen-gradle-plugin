package de.itemis.mps.envgen.environment

import de.itemis.mps.envgen.OS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.OS as JUnitOS

class UserOwnedFilesTest {

    @TempDir
    lateinit var tmp: File

    private val warnings = mutableListOf<String>()
    private fun generator() = EnvironmentGenerator(warn = { warnings += it }, overwriteHint = "use --overwrite")

    private fun mps(build: String?) = File(tmp, "mps-$build").apply {
        mkdirs()
        build?.let { File(this, "build.txt").writeText("MPS-$it\n") }
    }

    private fun spec(build: String? = "251.23774.423") = EnvironmentSpec(
        targetPath = File(tmp, ".mpsconfig"), environmentName = "Default", os = listOf(OS.LINUX),
        mpsPath = mps(build), missingMpsHint = "hint", generatorVersion = "1",
    )

    private fun options(spec: EnvironmentSpec) = File(spec.mpsConfigDir, "config/options")

    @Test
    fun `user owned files are written once and kept with a hint`() {
        val spec = spec()
        generator().generate(spec)
        val vmoptions = File(spec.mpsConfigDir, "mps64.vmoptions").apply { appendText("-Dmine=1\n") }
        val laf = File(options(spec), "laf.xml").apply { writeText("changed in MPS") }

        generator().generate(spec.copy(xmx = "6g", theme = Theme.DARK))

        assertTrue("-Dmine=1" in vmoptions.readText() && "-Xmx2048m" in vmoptions.readText())
        assertEquals("changed in MPS", laf.readText())
        val hint = warnings.single()
        assertTrue("xmx = 6g" in hint && "theme = DARK" in hint && "use --overwrite" in hint, hint)
        // the metadata describes the files, not the unapplied settings
        val stored = EnvironmentMetadata.read(spec.environmentDir, spec)!!.spec
        assertEquals("2048m", stored.xmx)
        assertEquals(Theme.LIGHT, stored.theme)
        assertFalse(File(spec.environmentDir, ".backup").exists())
    }

    @Test
    fun `explicit overwrite rewrites with backup`() {
        val spec = spec()
        generator().generate(spec)
        File(options(spec), "laf.xml").writeText("changed in MPS")

        generator().generate(spec.copy(theme = Theme.DARK), overwrite = setOf(EnvironmentPart.THEME))

        assertTrue("ExperimentalDark" in File(options(spec), "laf.xml").readText())
        val backup = File(spec.environmentDir, ".backup").listFiles()!!.single()
        assertEquals("changed in MPS", File(backup, "mps/config/options/laf.xml").readText())
        assertEquals(Theme.DARK, EnvironmentMetadata.read(spec.environmentDir, spec)!!.spec.theme)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `idea properties keep user lines but follow the location`() {
        val spec = spec()
        generator().generate(spec)
        File(spec.mpsConfigDir, "idea.properties").appendText("my.property=1\n")

        val moved = File(tmp, "moved")
        spec.environmentDir.renameTo(moved)
        val relocated = spec.copy(targetPath = tmp, environmentName = "moved")
        generator().generate(relocated)

        val text = File(relocated.mpsConfigDir, "idea.properties").readText()
        assertTrue("my.property=1" in text, text)
        assertTrue("idea.config.path=${relocated.mpsConfigDir}/config" in text, text)
        assertFalse(spec.environmentDir.path in text, text)
    }

    @Test
    fun `new UI builds get onboarding seen, light theme and compact density`() {
        val spec = spec("251.23774.423")
        generator().generate(spec)
        val options = options(spec)
        assertTrue("SHOW_WHATS_NEW_EDITOR\" value=\"false" in File(options, "updates.xml").readText())
        assertTrue("\"experimental.ui.used.version\": \"251.23774.423\"" in File(options, "other.xml").readText())
        assertTrue("\"whats.new.last.shown.version\": \"9999-9-0-mpsenvgen\"" in File(options, "other.xml").readText())
        assertTrue("ExperimentalLightWithLightHeader" in File(options, "laf.xml").readText())
        assertTrue("COMPACT" in File(options, "ui.lnf.xml").readText())
        // key and value on alternating lines, as EarlyAccessRegistryManager reads them; no UI switch, no theme reset
        assertEquals(listOf("ide.experimental.ui", "true"), File(options.parentFile, "early-access-registry.txt").readLines())
    }

    @Test
    fun `stored What's New version follows the format MPS parses`() {
        val spec = spec("251.23774.423")
        generator().generate(spec)
        val value = Regex(""""whats\.new\.last\.shown\.version": "([^"]+)"""")
            .find(File(options(spec), "other.xml").readText())!!.groupValues[1]
        // MPS (WhatsNewContent.ContentVersion.parse) splits at '-' and accepts year-release-eap[-hash] only
        val parts = value.split('-')
        assertTrue(parts.size in 3..4, value)
        assertTrue(parts[0].isNotEmpty() && parts[1].isNotEmpty() && parts[2].toIntOrNull() != null, value)
        assertTrue(parts[0] > "2100", "the year must stay newer than every real MPS release: $value")
    }

    @Test
    fun `classic UI builds keep the classic themes`() {
        listOf("241.18034.1", null).forEach { build ->
            val spec = spec(build).copy(environmentName = "env-$build")
            generator().generate(spec)
            val options = options(spec)
            assertTrue("JetBrainsLightTheme" in File(options, "laf.xml").readText())
            assertFalse(File(options, "ui.lnf.xml").exists())
            assertFalse(File(options, "other.xml").exists())
            assertFalse(File(options.parentFile, "early-access-registry.txt").exists())
            assertTrue(File(options, "updates.xml").isFile)
        }
        assertEquals(null, MpsBuild.of(mps("2026.1")))
    }

    @Test
    @EnabledOnOs(JUnitOS.LINUX)
    fun `start script restores removed port and debug lines`() {
        val spec = spec().copy(debugPort = 51500)
        File(spec.mpsPath, "bin/mps.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\ncat \"\$IDEA_VM_OPTIONS\"\n")
            setExecutable(true)
        }
        generator().generate(spec)
        val vmoptions = File(spec.mpsConfigDir, "mps64.vmoptions")
        vmoptions.writeText(vmoptions.readLines().filterNot { "internalPort" in it || "jdwp" in it }.joinToString("\n", postfix = "\n"))

        val script = File(spec.environmentDir, EnvironmentGenerator.startScriptName(OS.LINUX, "Default"))
        val process = ProcessBuilder(script.path, "-d").redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS))

        assertTrue(Regex("""(?m)^-Dide\.httpsupport\.internalPort=\d+$""").containsMatchIn(vmoptions.readText()))
        assertTrue(Regex("""(?m)^-agentlib:jdwp=.*address=127\.0\.0\.1:515\d\d$""").containsMatchIn(output), output)
        assertFalse("jdwp" in vmoptions.readText(), "the user's file gets no debug line")
    }
}
