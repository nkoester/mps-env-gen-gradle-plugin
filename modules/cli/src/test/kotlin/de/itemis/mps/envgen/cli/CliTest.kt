package de.itemis.mps.envgen.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.sun.net.httpserver.HttpServer
import de.itemis.mps.envgen.FakeNexus
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.JbrResolver
import de.itemis.mps.envgen.distribution.JbrVersion
import de.itemis.mps.envgen.distribution.MpsVersion
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.distribution.PreparedDistribution
import de.itemis.mps.envgen.environment.EnvironmentGenerator
import de.itemis.mps.envgen.environment.EnvironmentMetadata
import com.github.ajalt.clikt.core.context
import java.util.Properties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** serves a [FakeNexus] over HTTP and counts the downloads */
private class NexusServer(val root: File) : AutoCloseable {
    val downloads = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val file = File(root, exchange.requestURI.path.removePrefix("/"))
            if (file.isFile) {
                if (!file.name.endsWith(".sha1") && !file.name.endsWith(".pom") && !file.name.endsWith(".xml")) downloads.incrementAndGet()
                exchange.sendResponseHeaders(200, file.length())
                exchange.responseBody.use { file.inputStream().copyTo(it) }
            } else {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
        }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

class NexusArtifactSourceTest {

    @TempDir
    lateinit var tmp: File
    private lateinit var nexus: FakeNexus
    private lateinit var server: NexusServer
    private val platform = Platform.current

    @BeforeEach
    fun setup() {
        nexus = FakeNexus(File(tmp, "repo"))
        server = NexusServer(nexus.root)
    }

    @AfterEach
    fun teardown() = server.close()

    private fun source() = NexusArtifactSource(server.url, File(tmp, "cache"))

    @Test
    fun `downloads into the cache and reuses it`() {
        nexus.mps("2026.1")
        val first = source().mpsArchive(MpsVersion.parse("2026.1"))
        assertEquals(File(tmp, "cache/com/jetbrains/mps/2026.1/mps-2026.1.zip"), first)
        source().mpsArchive(MpsVersion.parse("2026.1"))
        assertEquals(1, server.downloads.get())
    }

    @Test
    fun `rejects a wrong checksum`() {
        nexus.mps("2026.1")
        File(nexus.root, "com/jetbrains/mps/2026.1/mps-2026.1.zip.sha1").writeText("0000")
        assertThrows<IOException> { source().mpsArchive(MpsVersion.parse("2026.1")) }
        assertTrue(File(tmp, "cache/com/jetbrains/mps/2026.1").listFiles().orEmpty().none { it.name.endsWith(".zip") })
    }

    @Test
    fun `reads marker, version listing and missing artifacts`() {
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbrVersionListing("21.0.8-b1163.69", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
        val source = source()
        assertEquals("25.0.3-b329.124", source.markerJbrVersion(MpsVersion.parse("2026.1")))
        assertNull(source.markerJbrVersion(MpsVersion.parse("2022.2.4")))
        assertEquals(listOf("21.0.8-b1163.69", "25.0.3-b329.124"), source.publishedJbrVersions())
        assertNull(source.jbrArchive(JbrVersion.parse("25.0.3-b1.1"), platform.jbrClassifier))
        assertTrue(source.jbrArchive(JbrVersion.parse("25.0.3-b329.124"), platform.jbrClassifier)!!.isFile)
    }
}

class LocalMpsLocatorTest {

    @TempDir
    lateinit var base: File

    private fun install(name: String) = File(base, "$name/bin").apply { mkdirs() }.parentFile

    @Test
    fun `finds installations by JetBrains naming`() {
        install("MPS-2024.1.5-linux")
        install("MPS-2024.1")
        val linux = install("MPS-2022.2.4-linux")
        install("MPS-2022.2.4-mac")
        val custom = install("my_mps_2025.3")
        File(base, "MPS-2023.2").mkdirs() // no bin: not an installation
        assertEquals(custom, LocalMpsLocator.find(base, MpsVersion.parse("2025.3"), OS.LINUX))
        assertEquals(custom, LocalMpsLocator.byName(base, "my_mps_2025.3"))

        assertEquals(File(base, "MPS-2024.1"), LocalMpsLocator.find(base, MpsVersion.parse("2024.1"), OS.LINUX))
        assertEquals(linux, LocalMpsLocator.find(base, MpsVersion.parse("2022.2.4"), OS.LINUX))
        assertNull(LocalMpsLocator.find(base, MpsVersion.parse("2023.2"), OS.LINUX))
        assertNull(LocalMpsLocator.find(base, MpsVersion.parse("2024.1.6"), OS.LINUX))
    }
}

class CommandsTest {

    @TempDir
    lateinit var tmp: File
    private lateinit var nexus: FakeNexus
    private lateinit var server: NexusServer
    private val platform = Platform.current
    private val clipboard = mutableListOf<String>()
    private val starts = mutableListOf<Pair<File, List<String>>>()
    private var startExitCode = 0
    /** answer to yes/no questions; null: no terminal */
    private var confirmAnswer: Boolean? = null
    private val questions = mutableListOf<String>()
    private val output = StringBuilder()
    private val target get() = File(tmp, ".mpsconfig")

    @BeforeEach
    fun setup() {
        nexus = FakeNexus(File(tmp, "repo"))
        server = NexusServer(nexus.root)
        nexus.mps("2026.1")
        nexus.marker("2026.1", "25.0.3-b329.124")
        nexus.jbr("25.0.3-b329.124", platform)
    }

    @AfterEach
    fun teardown() = server.close()

    private val services
        get() = CliServices(
            clipboard = { clipboard += it; true },
            runScript = { script, args -> starts += script to args; startExitCode },
            getenv = { null },
            tempDir = { File(tmp, "temp") },
            defaultsFile = { File(tmp, "defaults.properties") },
            confirm = { questions += it; confirmAnswer },
        )

    /** plugin folder with a descriptor for builds from [since]; FakeNexus 2026.1 is build 261.1.1 */
    private fun plugin(folder: String, id: String = "com.example.$folder", since: String = "261") =
        File(tmp, "plugins/$folder").apply {
            File(this, "META-INF").mkdirs()
            File(this, "META-INF/plugin.xml").writeText("<idea-plugin><id>$id</id><idea-version since-build=\"$since\"/></idea-plugin>")
        }

    private fun run(vararg args: String) {
        val command = MpsEnvGenCommand(services)
        command.context { echoMessage = { _, message, newline, _ -> output.append(message); if (newline) output.append('\n') } }
        val nexusArgs = if (args.first() in setOf("create", "copy", "refresh")) {
            listOf("--repository", server.url, "--cache-dir", File(tmp, "cache").path)
        } else emptyList()
        val targetArgs = if (args.first() == "init-defaults") emptyList()
        else listOf("--target", target.path, "--no-copy").filterNot { "--copy" in args && it == "--no-copy" }
        command.parse(listOf(args.first()) + targetArgs + nexusArgs + args.drop(1))
    }

    private fun env(name: String) = File(target, name)
    private fun script(name: String, dir: File = env(name)) = File(dir, EnvironmentGenerator.startScriptName(OS.current, name))
    private fun props(dir: File) = Properties().apply { File(dir, EnvironmentMetadata.FILE_NAME).reader().use { load(it) } }

    @Test
    fun `create prepares MPS, enables debugging and prints the start command`() {
        run("create", "2026.1")

        // shared by all environments in the cache folder, not next to the environment
        val dist = File(tmp, "cache/distributions/" + Platform.distributionDirName(MpsVersion.parse("2026.1"), platform.os))
        assertFalse(File(target, dist.name).exists())
        assertTrue(File(dist.parentFile, ".${dist.name}.lock").isFile, "hidden lock file next to the distribution")
        assertTrue(File(dist, PreparedDistribution.MARKER_FILE).isFile)
        assertTrue(File(dist, platform.homePath).normalize().path in script("2026.1").readText())
        assertTrue(script("2026.1").path in output, output.toString())
        assertTrue("address=127.0.0.1:" in File(env("2026.1"), "mps/mps64.vmoptions").readText())
        val props = props(env("2026.1"))
        assertEquals("NEXUS", props.getProperty("mps.origin"))
        assertEquals("false", props.getProperty("debug"))
        assertEquals("51337", props.getProperty("debug.port"))
        assertTrue(props.getProperty("created").isNotEmpty())
    }

    @Test
    fun `second environment gets other ports and existing names are refused`() {
        run("create", "2026.1")
        run("create", "2026.1", "--name", "second")
        val first = props(env("2026.1"))
        val second = props(env("second"))
        // debugging is off: both keep the base port, the start script picks a free one with -d
        assertEquals(first.getProperty("debug.port"), second.getProperty("debug.port"))
        // no fixed node URL port: the start scripts pick a free one at every start
        assertNull(first.getProperty("httpPort"))
        assertNull(second.getProperty("httpPort"))
        assertEquals(2, server.downloads.get(), "MPS and JBR are downloaded and prepared once")

        val e = assertThrows<CliktError> { run("create", "2026.1") }
        assertTrue("exists already" in e.message!!)
    }

    @Test
    fun `create from a path and from local installations`() {
        val installs = File(tmp, "installs")
        File(installs, "MPS-2026.1/bin").mkdirs()
        File(installs, "2020.3.5-linux/bin").mkdirs()
        run("create", "2026.1", "--installs", installs.path, "--no-download")
        assertEquals("LOCAL", props(env("2026.1")).getProperty("mps.origin"))
        // any folder name in --installs, no MPS- naming assumed
        run("create", "2020.3.5-linux", "--installs", installs.path, "--no-download")
        assertEquals(File(installs, "2020.3.5-linux").path, props(env("2020.3.5-linux")).getProperty("mps.path"))

        run("create", File(installs, "MPS-2026.1").path)
        assertEquals("PATH", props(env("MPS-2026.1")).getProperty("mps.origin"))
        assertEquals(0, server.downloads.get())
    }

    @Test
    fun `temp environments carry date and time`() {
        run("create", "2026.1", "--temp")
        val created = File(tmp, "temp").listFiles()!!.single { it.name.startsWith("2026.1-") }
        assertTrue(Regex("""2026\.1-\d{8}-\d{6}""").matches(created.name))
        assertTrue(script(created.name, created).isFile)

        // the cache, not the size limited temp folder
        val dist = Platform.distributionDirName(MpsVersion.parse("2026.1"), platform.os)
        assertFalse(File(tmp, "temp/$dist").exists())
        assertTrue(File(tmp, "cache/distributions/$dist/${PreparedDistribution.MARKER_FILE}").isFile)
        assertTrue(File(tmp, "cache/downloads/com/jetbrains/mps").isDirectory)
        assertTrue(File(tmp, "cache/distributions/$dist").path in script(created.name, created).readText())

        // a second throw-away environment and its refresh reuse it
        run("create", "2026.1", "--temp", "--name", "second")
        run("refresh", File(tmp, "temp/second").path)
        assertFalse(File(tmp, "temp/$dist").exists())
        assertEquals(2, server.downloads.get(), "MPS and JBR are downloaded once")
    }

    @Test
    fun `defaults file applies to create only`() {
        File(tmp, "defaults.properties").writeText("xmx=4096m\ntheme=dark\n")
        run("create", "2026.1")
        val vmoptions = File(env("2026.1"), "mps/mps64.vmoptions")
        assertTrue("-Xmx4096m" in vmoptions.readText())
        assertTrue("ExperimentalDark" in File(env("2026.1"), "mps/config/options/laf.xml").readText())

        run("refresh", "2026.1", "--xms", "512m")
        assertTrue("-Xmx4096m" in vmoptions.readText() && "-Xms512m" in vmoptions.readText())

        // the previous files are kept
        val backup = File(env("2026.1"), ".backup").listFiles()!!.single()
        assertTrue("-Xms1024m" in File(backup, "mps/mps64.vmoptions").readText())
        assertTrue(File(env("2026.1"), EnvironmentGenerator.CREATED_FILE).isFile)
    }

    @Test
    fun `init-defaults writes a template whose lines create honours`() {
        val file = File(tmp, "defaults.properties")
        run("init-defaults")
        val template = file.readText()
        listOf("#installs=", "#xmx=", "#theme=", "#target=.mpsconfig", "#copy=false", "#debug-suspend=true",
            "#model-check-before-generation=true", "environment: MPS_ENV_GEN_INSTALLS", "values: light|dark")
            .forEach { assertTrue(it in template, "'$it' missing in\n$template") }
        listOf("#name=", "#temp=", "#start=", "#start-debug=", "#help=").forEach { assertFalse(it in template, it) }
        assertTrue(template.lines().all { it.isEmpty() || it.startsWith("#") }, "everything is commented out")

        val e = assertThrows<CliktError> { run("init-defaults") }
        assertTrue("exists already" in e.message!!)

        val installs = File(tmp, "installs")
        File(installs, "MPS-2026.1/bin").mkdirs()
        file.writeText(template
            .replace("#installs=", "installs=${installs.path}")
            .replace("#debug-suspend=true", "debug-suspend=true")
            .replace("#model-check-before-generation=true", "model-check-before-generation=true"))
        run("create", "2026.1", "--no-download")
        val props = props(env("2026.1"))
        assertEquals("LOCAL", props.getProperty("mps.origin"))
        assertEquals("true", props.getProperty("debug.suspend"))
        assertEquals("true", props.getProperty("modelCheckBeforeGeneration"))
        // the command line still wins over the file
        run("create", "2026.1", "--name", "other", "--no-debug-suspend", "--no-download")
        assertEquals("false", props(env("other")).getProperty("debug.suspend"))

        val edited = file.readText()
        run("init-defaults", "--force")
        assertEquals(template, file.readText())
        assertEquals(edited, tmp.listFiles()!!.single { it.name.startsWith("defaults.properties.") && it.name.endsWith(".bak") }.readText())
    }

    @Test
    fun `init-defaults --print leaves the file alone`() {
        run("init-defaults", "--print")
        assertFalse(File(tmp, "defaults.properties").exists())
        assertTrue("#xmx=" in output)
    }

    @Test
    fun `copy takes settings and plugins but not caches`() {
        run("create", "2026.1", "--xmx", "3g")
        File(env("2026.1"), "mps/config/options/keymap.xml").writeText("mine")
        File(env("2026.1"), "mps/plugins/p/plugin.jar").apply { parentFile.mkdirs() }.writeText("jar")
        File(env("2026.1"), "mps/system/caches/x").apply { parentFile.mkdirs() }.writeText("cache")

        run("copy", "2026.1", "work", "--no-debug")
        val copy = env("work")
        assertEquals("mine", File(copy, "mps/config/options/keymap.xml").readText())
        assertTrue(File(copy, "mps/plugins/p/plugin.jar").isFile)
        assertFalse(File(copy, "mps/system").exists())
        assertTrue("-Xmx3g" in File(copy, "mps/mps64.vmoptions").readText())
        assertTrue(copy.path in File(copy, "mps/idea.properties").readText())
        assertEquals("false", props(copy).getProperty("debug"))
        assertTrue(props(copy).getProperty("history.0").contains("copied from"))
        assertNull(props(copy).getProperty("httpPort"))
    }

    @Test
    fun `update checks can be switched off and on again`() {
        run("create", "2026.1", "--no-update-checks")
        val updates = File(env("2026.1"), "mps/config/options/updates.xml")
        val other = File(env("2026.1"), "mps/config/options/other.xml").apply { writeText("changed in MPS") }
        assertTrue("<option name=\"CHECK_NEEDED\" value=\"false\" />" in updates.readText(), updates.readText())
        assertTrue("<option name=\"PLUGINS_CHECK_NEEDED\" value=\"false\" />" in updates.readText())
        assertEquals("false", props(env("2026.1")).getProperty("updateChecks"))

        run("refresh", "2026.1", "--update-checks")
        assertFalse("CHECK_NEEDED" in updates.readText(), updates.readText())
        assertEquals("changed in MPS", other.readText(), "only updates.xml is rewritten")
        assertEquals("true", props(env("2026.1")).getProperty("updateChecks"))
    }

    @Test
    fun `merge driver popup can be switched on and off again`() {
        run("create", "2026.1")
        val notifications = File(env("2026.1"), "mps/config/options/notifications.xml")
        assertTrue("displayType=\"NONE\"" in notifications.readText(), notifications.readText())
        assertEquals("false", props(env("2026.1")).getProperty("mergeDriverNotification"))

        run("refresh", "2026.1", "--merge-driver-notification")
        assertTrue("displayType=\"BALLOON\"" in notifications.readText(), notifications.readText())
        assertEquals("true", props(env("2026.1")).getProperty("mergeDriverNotification"))

        run("refresh", "2026.1", "--no-merge-driver-notification")
        assertTrue("displayType=\"NONE\"" in notifications.readText(), notifications.readText())
        assertEquals("false", props(env("2026.1")).getProperty("mergeDriverNotification"))
    }

    @Test
    fun `http port is fixed only on request`() {
        run("create", "2026.1")
        assertNull(props(env("2026.1")).getProperty("httpPort"))
        assertFalse("http port" in output, output.toString())
        assertTrue("HTTP_PORT=\"\"" in script("2026.1").readText())

        run("refresh", "2026.1", "--http-port", "63400")
        assertEquals("63400", props(env("2026.1")).getProperty("httpPort"))
        assertTrue("HTTP_PORT=\"63400\"" in script("2026.1").readText())
        assertTrue("http port  63400 (fixed)" in output, output.toString())

        run("refresh", "2026.1", "--http-port", "auto")
        assertNull(props(env("2026.1")).getProperty("httpPort"))
        assertThrows<CliktError> { run("refresh", "2026.1", "--http-port", "nope") }
    }

    @Test
    fun `rename moves the environment and refuses while MPS runs`() {
        run("create", "2026.1", "--name", "a")
        val lock = File(env("a"), "mps/system/port.lock").apply { parentFile.mkdirs(); writeText("") }
        // a leftover lock file of a closed MPS does not block
        assertFalse(isHeld(lock))
        java.io.RandomAccessFile(lock, "rw").use { raf ->
            raf.channel.lock().use { assertThrows<CliktError> { run("rename", "a", "b") } }
        }

        run("rename", env("a").path, "b")
        assertFalse(env("a").exists())
        assertTrue(script("b").isFile)
        assertFalse(File(env("b"), EnvironmentGenerator.startScriptName(OS.current, "a")).exists())
        assertTrue(env("b").path in File(env("b"), "mps/idea.properties").readText())
    }

    @Test
    fun `refresh keeps user changes, follows moves and re-prepares MPS`() {
        run("create", "2026.1", "--name", "a")
        val laf = File(env("a"), "mps/config/options/laf.xml").apply { writeText("user") }

        val moved = File(tmp, "elsewhere/b").apply { parentFile.mkdirs() }
        env("a").renameTo(moved)
        val dist = File(tmp, "cache/distributions/" + Platform.distributionDirName(MpsVersion.parse("2026.1"), platform.os))
        dist.deleteRecursively()

        run("refresh", moved.path)
        assertEquals("user", laf.let { File(moved, "mps/config/options/laf.xml") }.readText())
        assertTrue(moved.path in File(moved, "mps/idea.properties").readText())
        assertTrue(script("b", moved).isFile)
        // the deleted distribution is prepared in the cache again from the cached downloads
        assertTrue(File(dist, PreparedDistribution.MARKER_FILE).isFile)
        assertEquals(2, server.downloads.get())
    }

    @Test
    fun `copies the start command to the clipboard`() {
        val command = MpsEnvGenCommand(services)
        command.context { echoMessage = { _, message, newline, _ -> output.append(message); if (newline) output.append('\n') } }
        command.parse(listOf("create", "--target", target.path, "--repository", server.url, "--cache-dir", File(tmp, "cache").path, "2026.1"))
        assertEquals(listOf(script("2026.1").path), clipboard)
    }

    @Test
    fun `starts the environment via its script on request`() {
        run("create", "2026.1", "--name", "a")
        assertTrue(starts.isEmpty())

        run("create", "2026.1", "--name", "b", "--start")
        assertEquals(listOf(script("b") to emptyList<String>()), starts)

        if (OS.current == OS.LINUX) {
            run("refresh", "b", "--start=tmuxLA", "-d")
            assertEquals(script("b") to listOf("-d", "tmuxLA"), starts.last())
        }

        startExitCode = 3
        val e = assertThrows<ProgramResult> { run("copy", "a", "c", "--start") }
        assertEquals(3, e.statusCode)

        assertThrows<CliktError> { run("refresh", "a", "--start=nope") }
        assertThrows<CliktError> { run("create", "2026.1", "--name", "d", "--start=nope") }
        assertFalse(env("d").exists(), "invalid start options must not leave an environment behind")
        assertThrows<CliktError> { run("refresh", "a", "-d") }
    }

    @Test
    fun `short options in any order, optional values only attached`() {
        run("create", "-t", "-s", "-n", "a", "2026.1")
        assertEquals(listOf(script("a", File(tmp, "temp/a")) to emptyList<String>()), starts)
        run("create", "2026.1", "-ts", "-n", "b")
        assertEquals(script("b", File(tmp, "temp/b")) to emptyList<String>(), starts.last())

        run("create", "--debug", "-n", "c", "2026.1")
        assertEquals("true", props(env("c")).getProperty("debug"))
        run("create", "-x5005", "-n", "d", "2026.1")
        assertEquals("5005", props(env("d")).getProperty("debug.port"))
        run("create", "--debug=5006", "-n", "e", "2026.1")
        assertEquals("5006", props(env("e")).getProperty("debug.port"))
        run("create", "-x=5007", "-n", "f", "2026.1")
        assertEquals("5007", props(env("f")).getProperty("debug.port"))
        assertThrows<CliktError> { run("create", "-n", "g", "2026.1", "-x=nope") }

        if (OS.current == OS.LINUX) {
            run("refresh", "c", "-s=tmuxLA", "-d")
            assertEquals(script("c") to listOf("-d", "tmuxLA"), starts.last())
            run("refresh", "c", "-stmuxD")
            assertEquals(script("c") to listOf("tmuxD"), starts.last())
        }
    }

    @Test
    fun `prints completion scripts`() {
        val e = assertThrows<CliktError> { MpsEnvGenCommand(services).parse(listOf("completion", "bash")) }
        assertTrue("complete" in e.message!!)
        // without installations folder <mps> completes the packaged versions, no network needed
        assertTrue(Regex("""names=\([^)]*\b2022\.2\b[^)]*\b2026\.1\b[^)]*\)""").containsMatchIn(e.message!!), e.message)
        // installations complete as versions taken from their folder names
        assertTrue("BASH_REMATCH[2]" in e.message!!, e.message)
    }

    @Test
    fun `bash completes the version with options after the cursor`() {
        val e = assertThrows<CliktError> { MpsEnvGenCommand(services).parse(listOf("completion", "bash")) }
        val script = File(tmp, "completion.bash").apply { writeText(e.message!!) }
        fun complete(vararg words: String, cursor: Int): String {
            val call = "source '$script'; COMP_WORDS=(${words.joinToString(" ")}); COMP_CWORD=$cursor; _mps_env_gen; " +
                "echo \"${'$'}{COMPREPLY[*]}\""
            val process = ProcessBuilder("bash", "--norc", "--noprofile", "-c", call).redirectErrorStream(true)
                .apply { environment().keys.removeAll(setOf("MPS_ENV_GEN_INSTALLS", "MPS_BASE_PATH")) }
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
            return process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
        }
        assertEquals("2026.1 2026.1.1", complete("mps-env-gen", "create", "2026", cursor = 2))
        assertEquals("2026.1 2026.1.1", complete("mps-env-gen", "create", "2026", "--temp", "--start", cursor = 2))
    }

    @Test
    fun `packaged MPS versions are supported, ordered and parseable`() {
        val versions = PackagedMpsVersions.all.map(MpsVersion::parse)
        assertTrue(versions.size > 10, versions.toString())
        assertTrue(versions.all { it >= MpsVersion.FIRST_SUPPORTED }, versions.toString())
        assertTrue(versions.none { it in JbrResolver.UNSUPPORTED }, "versions that cannot be prepared are not offered")
        assertEquals(versions.sorted(), versions)
    }

    @Test
    fun `create installs plugins from options and plugin files, refresh changes and restores them`() {
        val tool = plugin("my-tool")
        File(tmp, "conf/mps-plugins.properties").apply { parentFile.mkdirs() }
            .writeText("other=../plugins/other\nother.property.a.b=1\n")
        plugin("other")

        run("create", "2026.1", "--plugin", tool.path, "--plugin-port", "my-tool:server.port=8600",
            "--plugins-file", File(tmp, "conf/mps-plugins.properties").path)
        val plugins = File(env("2026.1"), "mps/plugins")
        assertTrue(File(plugins, "my-tool/META-INF/plugin.xml").isFile)
        assertTrue(File(plugins, "other/META-INF/plugin.xml").isFile)
        assertEquals("other,my-tool", props(env("2026.1")).getProperty("plugins"))
        val vmoptions = File(env("2026.1"), "mps/mps64.vmoptions").readLines()
        assertTrue("-Da.b=1" in vmoptions && "-Dserver.port=8600" in vmoptions, vmoptions.toString())
        val script = script("2026.1").readText()
        assertTrue("mps-env-gen refresh ${env("2026.1")}" in script)

        File(plugins, "my-tool").deleteRecursively()
        File(plugins, "from-ide/lib.jar").apply { parentFile.mkdirs() }.writeText("jar")
        run("refresh", "2026.1", "--remove-plugin", "other")
        assertTrue(File(plugins, "my-tool").isDirectory, "restored")
        assertFalse(File(plugins, "other").exists())
        assertTrue(File(plugins, "from-ide").isDirectory)
        assertFalse("-Da.b=1" in File(env("2026.1"), "mps/mps64.vmoptions").readLines())

        run("copy", "2026.1", "work")
        assertTrue(File(env("work"), "mps/plugins/my-tool").isDirectory)
        assertEquals("my-tool", props(env("work")).getProperty("plugins"))

        run("refresh", "work", "--no-plugins")
        assertFalse(File(env("work"), "mps/plugins/my-tool").exists())
        assertTrue(File(env("work"), "mps/plugins/from-ide").isDirectory)
        assertThrows<CliktError> { run("refresh", "work", "--remove-plugin", "unknown") }
    }

    @Test
    fun `plugins of uncertain compatibility are asked for, incompatible ones refused`() {
        val older = plugin("older", since = "251")
        val e = assertThrows<CliktError> { run("create", "2026.1", "--plugin", older.path) }
        assertTrue("--plugin-compatible" in e.message!!, e.message)
        assertFalse(env("2026.1").exists(), "nothing written")

        confirmAnswer = false
        assertThrows<CliktError> { run("create", "2026.1", "--plugin", older.path) }
        assertTrue(questions.last().contains("built for 251 (2025.1)") && questions.last().endsWith("Install anyway? [y/N] "), questions.toString())

        confirmAnswer = true
        run("create", "2026.1", "--plugin", older.path)
        assertTrue(File(env("2026.1"), "mps/plugins/older").isDirectory)
        assertEquals("261.1.1", props(env("2026.1")).getProperty("plugin.older.confirmedFor"))

        confirmAnswer = null
        run("create", "2026.1", "--name", "declared", "--plugin", older.path, "--plugin-compatible", "older")
        assertTrue(File(env("declared"), "mps/plugins/older").isDirectory)

        val newer = plugin("newer", since = "271")
        val incompatible = assertThrows<CliktError> { run("create", "2026.1", "--name", "x", "--plugin", newer.path, "--plugin-compatible", "newer") }
        assertTrue("requires build 271 or later" in incompatible.message!!, incompatible.message)
    }

    @Test
    fun `plugin options are validated and offered in the defaults template as plugins file only`() {
        val tool = plugin("my-tool")
        assertThrows<CliktError> { run("create", "2026.1", "--plugin", tool.path, "--plugin-property", "unknown:a=1") }
        assertThrows<CliktError> { run("create", "2026.1", "--plugin", tool.path, "--plugin-port", "my-tool:p=x") }
        assertThrows<CliktError> { run("create", "2026.1", "--plugins-file", File(tmp, "missing.properties").path) }
        assertFalse(env("2026.1").exists())
        assertEquals("my-tool-1_0-2025_1".replace('_', '-'), PluginOptions.defaultName(File("my-tool-1.0-2025.1.zip")))

        run("init-defaults", "--print")
        assertTrue("#plugins-file=" in output, output.toString())
        listOf("#plugin=", "#plugin-property=", "#plugin-port=", "#plugin-compatible=", "#reinstall-plugins=")
            .forEach { assertFalse(it in output, it) }
    }

    @Test
    fun `validates arguments`() {
        assertThrows<CliktError> { run("create", "not-a-version") }
        assertThrows<CliktError> { run("create", "2026.1", "--name", "mps-2026.1-LINUX") }
        assertThrows<CliktError> { run("create", "2025.2.1") }
        assertThrows<CliktError> { run("refresh", "missing") }
    }
}
