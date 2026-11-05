package de.itemis.mps.envgen.plugins

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.environment.EnvironmentGenerator
import de.itemis.mps.envgen.environment.EnvironmentSpec
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

@EnabledOnOs(JUnitOS.LINUX, JUnitOS.MAC)
class PluginStartScriptTest {

    @TempDir
    lateinit var tmp: File

    private fun spec(vararg os: OS = arrayOf(OS.LINUX)): EnvironmentSpec {
        val mps = File(tmp, "mps").apply { mkdirs() }
        File(mps, "build.txt").writeText("MPS-251.28774.766")
        File(mps, "bin/mps.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\necho MPS STARTED\n")
            setExecutable(true)
        }
        return EnvironmentSpec(
            targetPath = File(tmp, ".mpsconfig"), environmentName = "env", os = os.toList(), mpsPath = mps,
            missingMpsHint = "hint", generatorVersion = "1", pluginRestoreHint = "mps-env-gen refresh env",
        )
    }

    private fun plugin(name: String, id: String, since: String = "251", ports: Map<String, Int> = emptyMap(), folder: String = name): PluginDeclaration {
        val dir = File(tmp, "sources/$folder/META-INF").apply { mkdirs() }
        File(dir, "plugin.xml").writeText("<idea-plugin><id>$id</id><idea-version since-build=\"$since\"/></idea-plugin>")
        return PluginDeclaration(name, "sources/$name", dir.parentFile, ports = ports)
    }

    private fun run(spec: EnvironmentSpec): Pair<Int, String> {
        val script = File(spec.environmentDir, EnvironmentGenerator.startScriptName(OS.LINUX, spec.environmentName))
        val process = ProcessBuilder(script.path).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    @Test
    fun `lists no plugins`() {
        val spec = spec()
        EnvironmentGenerator().generate(spec)
        val (exit, output) = run(spec)
        assertEquals(0, exit, output)
        assertTrue("Plugins: none managed" in output, output)
        assertTrue("MPS STARTED" in output, output)
    }

    @Test
    fun `lists managed and other plugins and warns about missing, disabled and uncertain ones`() {
        val spec = spec().copy(plugins = listOf(plugin("tool", "com.example.tool"), plugin("old", "com.example.old", since = "222"), plugin("gone", "com.example.gone")))
        EnvironmentGenerator(acceptUncertainPlugin = { true }).generate(spec)
        val plugins = File(spec.mpsConfigDir, "plugins")
        File(plugins, "gone").deleteRecursively()
        File(plugins, "from-ide").mkdirs()
        File(spec.mpsConfigDir, "config/disabled_plugins.txt").apply { parentFile.mkdirs(); writeText("com.example.tool\n") }

        val (exit, output) = run(spec)
        assertEquals(0, exit, output)
        assertTrue("Plugins (managed by mps-env-gen):" in output, output)
        assertTrue(Regex("""(?m)^  tool  sources/tool  \(com\.example\.tool\)$""").containsMatchIn(output), output)
        assertTrue("Other plugins in mps/plugins: from-ide" in output, output)
        assertTrue("WARNING: plugin 'gone': folder plugins/gone is missing; restore: mps-env-gen refresh env" in output, output)
        assertTrue("WARNING: plugin 'tool' (com.example.tool) is disabled in config/disabled_plugins.txt" in output, output)
        assertTrue(Regex("""WARNING: Plugin 'old' may not be compatible: .*built for 222 .*\(installed anyway\)""").containsMatchIn(output), output)
        assertTrue("MPS STARTED" in output, output)
    }

    @Test
    fun `refuses to start MPS if a plugin port is busy`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { busy ->
            val spec = spec().copy(plugins = listOf(plugin("tool", "com.example.tool", ports = mapOf("server.port" to busy.localPort))))
            EnvironmentGenerator().generate(spec)
            val (exit, output) = run(spec)
            assertEquals(1, exit, output)
            assertTrue("ERROR: port ${busy.localPort} of plugin 'tool' (server.port) is in use; MPS is not started." in output, output)
            assertFalse("MPS STARTED" in output, output)
        }
    }

    @Test
    fun `shell scripts with plugins are valid sh and bash, batch scripts contain the checks`() {
        val spec = spec(OS.LINUX, OS.MAC, OS.WINDOWS).copy(plugins = listOf(plugin("t-x", "com.example.\"quoted\"", ports = mapOf("server.port" to 8600), folder = "t\$x")))
        EnvironmentGenerator().generate(spec)
        listOf("bash" to OS.LINUX, "sh" to OS.MAC).forEach { (shell, os) ->
            val script = File(spec.environmentDir, EnvironmentGenerator.startScriptName(os, spec.environmentName))
            val process = ProcessBuilder(shell, "-n", script.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), "$shell -n $script: $output")
        }
        val bat = File(spec.environmentDir, EnvironmentGenerator.startScriptName(OS.WINDOWS, spec.environmentName)).readText()
        assertTrue("SET \"MANAGED_PLUGIN_FOLDERS= t\$x \"" in bat, bat)
        assertTrue("netstat -an -p TCP | findstr /C:\":8600 \" | findstr LISTENING >NUL || GOTO :pluginPortFree_0_0" in bat, bat)
        assertTrue("ECHO ERROR: port 8600 of plugin 't-x' ^(server.port^) is in use; MPS is not started." in bat, bat)
        assertFalse("REPLACE_ME__" in bat)
    }
}
