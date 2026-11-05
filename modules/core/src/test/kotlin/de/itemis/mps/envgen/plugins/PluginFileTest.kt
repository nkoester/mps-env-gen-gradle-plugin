package de.itemis.mps.envgen.plugins

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.environment.EnvironmentGenerator
import de.itemis.mps.envgen.environment.EnvironmentSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginFileTest {

    @TempDir
    lateinit var tmp: File

    private fun file(text: String) = File(tmp, "conf/mps-plugins.properties").apply { parentFile.mkdirs(); writeText(text.trimIndent()) }

    @Test
    fun `reads declarations in file order with settings and resolved paths`() {
        val declarations = PluginFile.read(file("""
            # tooling
            tool=../tools/tool.zip
            tool.property.some.key=a value
            tool.port.server.port=8600
            tool.compatible=true
            other=~/plugins/other
            abs=${File(tmp, "abs").path.replace("\\", "\\\\")}
        """))

        assertEquals(listOf("tool", "other", "abs"), declarations.map { it.name })
        val tool = declarations[0]
        assertEquals("../tools/tool.zip", tool.source)
        assertEquals(File(tmp, "tools/tool.zip").absoluteFile, tool.path)
        assertEquals(mapOf("some.key" to "a value"), tool.systemProperties)
        assertEquals(mapOf("server.port" to 8600), tool.ports)
        assertTrue(tool.compatible)
        assertEquals(File(System.getProperty("user.home"), "plugins/other").absoluteFile.normalize(), declarations[1].path)
        assertEquals(File(tmp, "abs").absoluteFile, declarations[2].path)
    }

    @Test
    fun `reports file and key of errors`() {
        listOf(
            "a.b=x" to "a.b: unknown key",
            "a=x\na.port.p=99999" to "a.port.p: '99999' is not a port",
            "a=x\na.compatible=yes" to "a.compatible: 'yes' is neither",
            "a=" to "a: no zip or folder",
            "a.property.k=v" to "a: settings for plugin 'a', which is not declared",
        ).forEach { (text, expected) ->
            val e = assertFailsWith<PluginInstallationException> { PluginFile.read(file(text)) }
            assertTrue(e.message!!.contains("mps-plugins.properties, $expected"), e.message)
        }
        assertFailsWith<PluginInstallationException> { PluginFile.read(File(tmp, "missing.properties")) }
    }

    @Test
    fun `later declarations replace earlier ones of the same name`() {
        fun d(name: String, source: String) = PluginDeclaration(name, source, File(source))
        assertEquals(listOf(d("a", "2"), d("b", "1")), PluginFile.merge(listOf(d("a", "1"), d("b", "1")), listOf(d("a", "2"))))
    }

    @Test
    fun `vmoptions block is replaced in place of user content and removed without properties`() {
        val vmoptions = File(tmp, "mps64.vmoptions").apply { writeText("-Xmx2g\n-Dserver.port=1\n") }
        val tool = PluginDeclaration("tool", "p.zip", File("p.zip"), mapOf("a" to "1"), mapOf("server.port" to 8600))

        PluginVmOptions.update(vmoptions, listOf(tool))
        PluginVmOptions.update(vmoptions, listOf(tool))
        assertEquals(
            listOf("-Xmx2g", "-Dserver.port=1", PluginVmOptions.BEGIN, "#tool", "-Da=1", "-Dserver.port=8600", PluginVmOptions.END),
            vmoptions.readLines(),
        )
        assertEquals(listOf("-Dserver.port=1"), PluginVmOptions.overridden(listOf(tool), listOf("-Xmx2g", "-Dserver.port=1")))

        vmoptions.appendText("-Duser.line=1\n")
        PluginVmOptions.update(vmoptions, listOf(tool.copy(systemProperties = emptyMap(), ports = emptyMap())))
        assertEquals(listOf("-Xmx2g", "-Dserver.port=1", "-Duser.line=1"), vmoptions.readLines())

        val clash = PluginDeclaration("other", "o.zip", File("o.zip"), ports = mapOf("server.port" to 1))
        assertFailsWith<PluginInstallationException> { PluginVmOptions.check(listOf(tool, clash)) }
    }

    @Test
    fun `generator writes the block also into kept vmoptions`() {
        val mps = File(tmp, "mps").apply { mkdirs() }
        val source = File(tmp, "src/p/META-INF").apply { mkdirs() }
        File(source, "plugin.xml").writeText("<idea-plugin><id>p</id><idea-version since-build=\"251\"/></idea-plugin>")
        val spec = EnvironmentSpec(
            targetPath = File(tmp, ".mpsconfig"), environmentName = "env", os = listOf(OS.LINUX), mpsPath = mps,
            missingMpsHint = "hint", generatorVersion = "1",
        )
        val generator = EnvironmentGenerator(acceptUncertainPlugin = { true })
        generator.generate(spec)
        val vmoptions = File(spec.mpsConfigDir, "mps64.vmoptions")
        vmoptions.appendText("-Dmine=1\n")

        generator.generate(spec.copy(plugins = listOf(PluginDeclaration("p", "p", source.parentFile, ports = mapOf("server.port" to 8600)))))
        val lines = vmoptions.readLines()
        assertTrue("-Dmine=1" in lines)
        assertEquals(listOf(PluginVmOptions.BEGIN, "#p", "-Dserver.port=8600", PluginVmOptions.END), lines.takeLast(4))
    }
}
