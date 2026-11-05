package de.itemis.mps.envgen.plugins

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.environment.EnvironmentGenerator
import de.itemis.mps.envgen.environment.EnvironmentMetadata
import de.itemis.mps.envgen.environment.EnvironmentSpec
import de.itemis.mps.envgen.environment.MpsBuild
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginInstallerTest {

    @TempDir
    lateinit var tmp: File

    private val build = MpsBuild("251.28774.766")

    private fun pluginXml(id: String, since: String? = "251", until: String? = null, depends: List<String> = emptyList()) = buildString {
        append("<idea-plugin><id>$id</id><name>$id</name><version>1.0</version>")
        if (since != null || until != null) {
            append("<idea-version")
            since?.let { append(" since-build=\"$it\"") }
            until?.let { append(" until-build=\"$it\"") }
            append("/>")
        }
        depends.forEach { append("<depends>$it</depends>") }
        append("<depends optional=\"true\" config-file=\"x.xml\">optional.dep</depends>")
        append("<extensions><id>nested</id></extensions></idea-plugin>")
    }

    private fun jar(xml: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("META-INF/plugin.xml")); it.write(xml.toByteArray()) }
    }.toByteArray()

    /** zip with the given entries; values are file contents */
    private fun zip(name: String, entries: Map<String, ByteArray>): File = File(tmp, name).also { file ->
        ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (path, data) -> out.putNextEntry(ZipEntry(path)); out.write(data) } }
    }

    /** IntelliJ layout: `<folder>/lib/<folder>.jar` with the descriptor, next to a broken jar sorted first */
    private fun intellijPlugin(folder: String, xml: String) = mapOf("$folder/lib/$folder.jar" to jar(xml), "$folder/lib/0-broken.jar" to ByteArray(0))

    private fun fakeMps(): File = File(tmp, "mps").apply {
        mkdirs()
        File(this, "build.txt").writeText("MPS-${build.number}")
        File(this, "plugins/vcs-git/lib").mkdirs()
        File(this, "plugins/vcs-git/lib/git.jar").writeBytes(jar(pluginXml("Git4Idea")))
    }

    private val pluginsDir get() = File(tmp, "env/mps/plugins")

    private fun installer(accept: (String) -> Boolean = { false }, warnings: MutableList<String> = mutableListOf()) =
        PluginInstaller(pluginsDir, fakeMps(), build, acceptUncertain = accept, warn = { warnings += it })

    private fun declaration(name: String, source: File, compatible: Boolean = false) =
        PluginDeclaration(name, source.name, source, compatible = compatible)

    @Test
    fun `reads descriptor without optional and nested elements`() {
        val descriptor = PluginDescriptor.parse(pluginXml("a.b", "251", "251.*", listOf("Git4Idea")).byteInputStream(), "test")
        assertEquals(PluginDescriptor("a.b", "1.0", "251", "251.*", listOf("Git4Idea")), descriptor)
    }

    @Test
    fun `accepts IntelliJ doctypes but loads no external entities`() {
        val intellij = "<!DOCTYPE idea-plugin PUBLIC \"Plugin/DTD\" \"http://plugins.intellij.net/plugin.dtd\"><idea-plugin><id>a</id></idea-plugin>"
        assertEquals("a", PluginDescriptor.parse(intellij.byteInputStream(), "test").id)

        val secret = File(tmp, "secret.txt").apply { writeText("secret") }
        val xxe = "<!DOCTYPE x [<!ENTITY e SYSTEM \"${secret.toURI()}\">]><idea-plugin><id>a&e;</id></idea-plugin>"
        val id = runCatching { PluginDescriptor.parse(xxe.byteInputStream(), "test").id }.getOrNull()
        assertFalse(id.orEmpty().contains("secret"))
    }

    @Test
    fun `compatibility follows idea-version and flags other branches without until-build`() {
        fun check(since: String?, until: String?) = Compatibility.of(PluginDescriptor("x", null, since, until, emptyList()), build)
        assertIs<Compatibility.Compatible>(check("251", null))
        assertIs<Compatibility.Compatible>(check("222", "251.*"))
        assertIs<Compatibility.Compatible>(check("222", "251"))
        assertIs<Compatibility.Incompatible>(check("261", null))
        assertIs<Compatibility.Incompatible>(check("251.30000", null))
        assertIs<Compatibility.Incompatible>(check("222", "243.*"))
        assertIs<Compatibility.Uncertain>(check("222", null))
        assertIs<Compatibility.Uncertain>(check(null, null))
        assertIs<Compatibility.Uncertain>(Compatibility.of(null, build))
        assertIs<Compatibility.Uncertain>(Compatibility.of(PluginDescriptor("x", null, "251", null, emptyList()), null))
        assertEquals("2026.1", MpsBuild("261.1").series)
        assertEquals("2022.2", MpsBuild.seriesOf(222))
    }

    @Test
    fun `installs IntelliJ and MPS layouts, bundles and archives without top folder`() {
        val tool = zip("tool.zip", intellijPlugin("my-tool", pluginXml("com.example.tool", depends = listOf("Git4Idea", "com.intellij.modules.platform"))))
        val bundle = zip("bundle.zip", mapOf(
            "bundle/a/META-INF/plugin.xml" to pluginXml("a").toByteArray(),
            "bundle/b/META-INF/plugin.xml" to pluginXml("b").toByteArray(),
            "bundle/readme.txt" to ByteArray(0),
        ))
        val flat = zip("flat.zip", mapOf("META-INF/plugin.xml" to pluginXml("flat").toByteArray()))
        val warnings = mutableListOf<String>()

        val installed = installer(warnings = warnings)
            .prepare(listOf(declaration("tool", tool), declaration("bundle", bundle), declaration("flat", flat)), emptyList())
            .apply()

        assertEquals(listOf("my-tool"), installed[0].folders)
        assertEquals(listOf("com.example.tool"), installed[0].ids)
        assertEquals(listOf("a", "b"), installed[1].folders)
        assertEquals(listOf("flat"), installed[2].folders)
        assertTrue(File(pluginsDir, "my-tool/lib/my-tool.jar").isFile)
        assertTrue(File(pluginsDir, "a/META-INF/plugin.xml").isFile)
        assertTrue(File(pluginsDir, "flat/META-INF/plugin.xml").isFile)
        assertFalse(File(pluginsDir.parentFile, ".plugins-staging").exists())
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `installs from a folder`() {
        val source = File(tmp, "src/my-plugin/META-INF").apply { mkdirs() }
        File(source, "plugin.xml").writeText(pluginXml("my"))
        val installed = installer().prepare(listOf(declaration("my", source.parentFile)), emptyList()).apply()
        assertEquals(listOf("my-plugin"), installed.single().folders)
        assertTrue(File(source, "plugin.xml").isFile, "source folders are copied, not moved")
    }

    @Test
    fun `keeps unchanged plugins, replaces changed ones and removes undeclared ones`() {
        val a = zip("a.zip", intellijPlugin("a", pluginXml("a")))
        val b = zip("b.zip", intellijPlugin("b", pluginXml("b")))
        val first = installer().prepare(listOf(declaration("a", a), declaration("b", b)), emptyList()).apply()
        val marker = File(pluginsDir, "a/marker").apply { writeText("kept") }
        File(pluginsDir, "foreign").mkdirs()

        val second = installer().prepare(listOf(declaration("a", a)), first).apply()
        assertTrue(marker.isFile, "unchanged plugin is not reinstalled")
        assertFalse(File(pluginsDir, "b").exists())
        assertTrue(File(pluginsDir, "foreign").isDirectory)

        zip("a.zip", intellijPlugin("a2", pluginXml("a")))
        val third = installer().prepare(listOf(declaration("a", a)), second).apply()
        assertEquals(listOf("a2"), third.single().folders)
        assertFalse(File(pluginsDir, "a").exists())

        File(pluginsDir, "a2").deleteRecursively()
        installer().prepare(listOf(declaration("a", a)), third).apply()
        assertTrue(File(pluginsDir, "a2").isDirectory, "missing folder is restored")
    }

    @Test
    fun `keeps the installed copy if the source is gone`() {
        val a = zip("a.zip", intellijPlugin("a", pluginXml("a")))
        val installed = installer().prepare(listOf(declaration("a", a)), emptyList()).apply()
        a.delete()
        val warnings = mutableListOf<String>()
        val again = installer(warnings = warnings).prepare(listOf(declaration("a", a)), installed).apply()
        assertEquals(installed, again)
        assertTrue(File(pluginsDir, "a/lib/a.jar").isFile)
        assertTrue(warnings.single().contains("keeping the installed copy"), warnings.toString())

        assertFailsWith<PluginInstallationException> { installer().prepare(listOf(declaration("a", a)), installed, reinstall = true) }
        File(pluginsDir, "a").deleteRecursively()
        assertFailsWith<PluginInstallationException> { installer().prepare(listOf(declaration("a", a)), installed) }
    }

    @Test
    fun `refuses folders installed otherwise unless reinstalling`() {
        val a = zip("a.zip", intellijPlugin("a", pluginXml("a")))
        File(pluginsDir, "a").mkdirs()
        assertFailsWith<PluginInstallationException> { installer().prepare(listOf(declaration("a", a)), emptyList()) }
        installer().prepare(listOf(declaration("a", a)), emptyList(), reinstall = true).apply()
        assertTrue(File(pluginsDir, "a/lib/a.jar").isFile)
    }

    @Test
    fun `fails before writing on duplicates, missing sources, empty archives and incompatible plugins`() {
        val a = zip("a.zip", intellijPlugin("a", pluginXml("same")))
        val b = zip("b.zip", intellijPlugin("b", pluginXml("same")))
        val empty = zip("empty.zip", mapOf("readme.txt" to ByteArray(0)))
        val newer = zip("newer.zip", intellijPlugin("n", pluginXml("n", since = "261")))

        val failures = listOf(
            listOf(declaration("a", a), declaration("b", b)),
            listOf(declaration("a", a), declaration("a", b)),
            listOf(declaration("x", File(tmp, "missing.zip"))),
            listOf(declaration("e", empty)),
            listOf(declaration("n", newer, compatible = true)),
        )
        failures.forEach { declarations ->
            assertFailsWith<PluginInstallationException> { installer().prepare(declarations, emptyList()) }
            assertFalse(pluginsDir.exists(), "nothing installed for $declarations")
            assertFalse(File(pluginsDir.parentFile, ".plugins-staging").exists())
        }
    }

    @Test
    fun `uncertain plugins are asked for once per MPS build, unless declared compatible`() {
        val old = zip("old.zip", intellijPlugin("old", pluginXml("old", since = "222")))
        var asked = 0

        assertFailsWith<PluginInstallationException> { installer(accept = { asked++; false }).prepare(listOf(declaration("old", old)), emptyList()) }
        val warnings = mutableListOf<String>()
        val installed = installer(accept = { asked++; true }, warnings).prepare(listOf(declaration("old", old)), emptyList()).apply()
        assertEquals(2, asked)
        assertEquals(build.number, installed.single().confirmedFor)
        assertTrue(installed.single().warnings.single().contains("built for 222 (2022.2)"))

        val again = installer(accept = { asked++; false }).prepare(listOf(declaration("old", old)), installed).apply()
        assertEquals(2, asked, "confirmed for this build")
        assertEquals(installed.single().warnings, again.single().warnings)

        val declared = installer(accept = { asked++; false }).prepare(listOf(declaration("old", old, compatible = true)), emptyList(), reinstall = true).apply()
        assertEquals(2, asked)
        assertEquals(emptyList(), declared.single().warnings)
    }

    @Test
    fun `warns about missing dependencies and replaced bundled plugins`() {
        val a = zip("a.zip", intellijPlugin("a", pluginXml("a", depends = listOf("Git4Idea", "b", "missing.plugin"))))
        val b = zip("b.zip", intellijPlugin("b", pluginXml("b")))
        val git = zip("git.zip", intellijPlugin("git", pluginXml("Git4Idea")))
        val warnings = mutableListOf<String>()

        val installed = installer(warnings = warnings)
            .prepare(listOf(declaration("a", a), declaration("b", b), declaration("git", git)), emptyList()).apply()

        assertEquals(listOf("Plugin 'a' (a) needs missing.plugin, which is neither bundled with MPS nor installed"), installed[0].warnings)
        assertEquals(listOf("Plugin 'git' (Git4Idea) replaces the plugin of the same ID bundled with MPS"), installed[2].warnings)
        assertEquals(installed.flatMap { it.warnings }, warnings)
    }

    @Test
    fun `generator installs plugins, stores them and leaves no new environment behind on failure`() {
        val mps = fakeMps()
        val spec = EnvironmentSpec(
            targetPath = File(tmp, ".mpsconfig"), environmentName = "env", os = listOf(OS.LINUX), mpsPath = mps,
            missingMpsHint = "hint", generatorVersion = "1",
        )
        val tool = zip("tool.zip", intellijPlugin("my-tool", pluginXml("com.example.tool")))
        val declared = PluginDeclaration("tool", "tool.zip", tool, systemProperties = mapOf("a.b" to "1"), ports = mapOf("server.port" to 8600))

        EnvironmentGenerator().generate(spec.copy(plugins = listOf(declared)))
        assertTrue(File(spec.mpsConfigDir, "plugins/my-tool/lib/my-tool.jar").isFile)
        val stored = EnvironmentMetadata.read(spec.environmentDir, spec)!!
        assertEquals(listOf(declared), stored.spec.plugins)
        assertEquals(listOf("my-tool"), stored.plugins.single().folders)

        // regenerating without the plugins part keeps the stored state
        EnvironmentGenerator().refreshStartScripts(spec)
        assertEquals(stored.plugins, EnvironmentMetadata.read(spec.environmentDir, spec)!!.plugins)

        val newer = zip("newer.zip", intellijPlugin("n", pluginXml("n", since = "261")))
        val other = spec.copy(environmentName = "other", plugins = listOf(declaration("n", newer)))
        assertFailsWith<PluginInstallationException> { EnvironmentGenerator().generate(other) }
        assertFalse(other.environmentDir.exists())
    }
}
