package de.itemis.mps.envgen.environment

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS as JUnitOS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentMaintenanceTest {

    @TempDir
    lateinit var tmp: File

    private fun spec(name: String = "Default", vararg os: OS = arrayOf(OS.LINUX)) = EnvironmentSpec(
        targetPath = File(tmp, ".mpsconfig"), environmentName = name, os = os.toList(),
        mpsPath = File(tmp, "mps").apply { mkdirs() }, missingMpsHint = "hint", generatorVersion = "1",
    )

    @Test
    fun `metadata round trip keeps all settings and history`() {
        val spec = spec().copy(
            xmx = "6g", debug = true, debugPort = 51340, httpPort = 63330, theme = Theme.DARK,
            vmOptions = listOf("-Da=1", "-Db=2"), ideaProperties = listOf("x=y"), mpsOrigin = MpsOrigin.NEXUS,
            mpsSource = "2026.1", jbrVersion = "25.0.3-b329.124", projectPath = File(tmp, "project"),
        )
        EnvironmentMetadata.write(spec, "created")
        EnvironmentMetadata.write(spec, "refreshed")

        val stored = EnvironmentMetadata.read(spec.environmentDir, spec())!!
        assertEquals(spec.copy(targetPath = spec.targetPath.absoluteFile), stored.spec)
        assertEquals(2, stored.history.size)
        assertTrue(stored.created!!.isNotEmpty())
    }

    @Test
    fun `ports skip siblings and bound ports`() {
        val target = File(tmp, ".mpsconfig")
        EnvironmentMetadata.write(spec("a").copy(httpPort = 63320, debug = true, debugPort = 51337))
        val used = Ports.usedBySiblings(target)
        assertEquals(setOf(63320), used.http)
        assertEquals(51338, Ports.allocate(Ports.DEBUG_BASE, used.debug) { true })
        assertEquals(51339, Ports.allocate(Ports.DEBUG_BASE, used.debug) { it != 51338 })
        assertEquals(emptySet(), Ports.usedBySiblings(target, exclude = File(target, "a")).http)
    }

    @Test
    fun `removes start scripts of former names and OS`() {
        val generator = EnvironmentGenerator()
        generator.generate(spec("a", OS.LINUX, OS.WINDOWS))
        val dir = File(tmp, ".mpsconfig/a")
        File(dir, "startMPS_LINUX_old.sh").writeText("old")

        generator.generate(spec("a", OS.LINUX), setOf(EnvironmentPart.START_SCRIPTS))
        assertEquals(listOf("startMPS_LINUX_a.sh"), dir.list()!!.filter { it.startsWith("startMPS_") })
    }

    @Test
    fun `model check can be enabled again`() {
        val generator = EnvironmentGenerator()
        generator.generate(spec())
        val file = File(tmp, ".mpsconfig/Default/mps/config/options/generationSettings.xml")
        assertTrue("value=\"false\"" in file.readText())
        generator.generate(spec().copy(modelCheckBeforeGeneration = true), overwrite = setOf(EnvironmentPart.MODEL_CHECK))
        assertTrue("value=\"true\"" in file.readText())
    }

    @Test
    fun `reserved names are the distribution folders only`() {
        assertTrue(Platform.isReservedEnvironmentName("mps-2026.1-LINUX"))
        assertTrue(Platform.isReservedEnvironmentName("mps-2026.1-RC1-MAC"))
        assertFalse(Platform.isReservedEnvironmentName("MPS-2026.1"))
        assertFalse(Platform.isReservedEnvironmentName("mps-dev"))
    }

    /** runs the Linux start script of [spec] against a fake MPS printing its vmoptions */
    private fun startAndReadVmOptions(spec: EnvironmentSpec, vararg args: String): String {
        File(tmp, "mps/bin/mps.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\necho \"VMOPTIONS=\$IDEA_VM_OPTIONS\"\ncat \"\$IDEA_VM_OPTIONS\"\n")
            setExecutable(true)
        }
        EnvironmentGenerator().generate(spec)
        val script = File(spec.environmentDir, EnvironmentGenerator.startScriptName(OS.LINUX, spec.environmentName))
        val process = ProcessBuilder(listOf(script.path) + args).redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS))
        return output
    }

    @Test
    @EnabledOnOs(JUnitOS.LINUX)
    fun `start script moves debugging to a free port`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { busy ->
            val spec = spec().copy(debug = true, debugPort = busy.localPort)
            val output = startAndReadVmOptions(spec)
            assertTrue("VMOPTIONS=${spec.mpsConfigDir}/mps64.runtime.vmoptions" in output, output)
            assertTrue(Regex("""(?m)^-agentlib:jdwp=.*address=127\.0\.0\.1:${busy.localPort + 1}$""").containsMatchIn(output), output)
        }
    }

    @Test
    @EnabledOnOs(JUnitOS.LINUX)
    fun `debugging is off by default and enabled per start with -d`() {
        val spec = spec().copy(debug = false, debugPort = 51400)
        val off = startAndReadVmOptions(spec)
        assertTrue(Regex("""(?m)^#-agentlib:jdwp=""").containsMatchIn(off), off)
        // without debugging MPS uses the user's file directly
        assertTrue("VMOPTIONS=${spec.mpsConfigDir}/mps64.vmoptions" in off, off)

        val on = startAndReadVmOptions(spec, "-d")
        assertTrue("VMOPTIONS=${spec.mpsConfigDir}/mps64.runtime.vmoptions" in on, on)
        assertTrue(Regex("""(?m)^-agentlib:jdwp=.*suspend=n,address=127\.0\.0\.1:514\d\d$""").containsMatchIn(on), on)
        // the environment's own vmoptions stay untouched
        assertTrue(Regex("""(?m)^#-agentlib:jdwp=""").containsMatchIn(File(spec.mpsConfigDir, "mps64.vmoptions").readText()))
    }

    @Test
    @EnabledOnOs(JUnitOS.LINUX)
    fun `start script moves the node URL port to a free one`() {
        val mps = File(tmp, "mps")
        File(mps, "bin/mps.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\ngrep internalPort \"\$IDEA_VM_OPTIONS\"\n")
            setExecutable(true)
        }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { busy ->
            val spec = spec().copy(httpPort = busy.localPort)
            EnvironmentGenerator().generate(spec)
            val script = File(spec.environmentDir, EnvironmentGenerator.startScriptName(OS.LINUX, "Default"))
            val process = ProcessBuilder(script.path).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(30, TimeUnit.SECONDS))
            assertTrue("-Dide.httpsupport.internalPort=${busy.localPort + 1}" in output, output)
        }
    }

    @Test
    fun `creation timestamp is written once`() {
        val generator = EnvironmentGenerator()
        generator.generate(spec())
        val created = File(tmp, ".mpsconfig/Default/${EnvironmentGenerator.CREATED_FILE}")
        val first = created.readText()
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*""").matches(first.trim()), first)
        created.writeText("kept")
        generator.generate(spec())
        assertEquals("kept", created.readText())
    }
}
