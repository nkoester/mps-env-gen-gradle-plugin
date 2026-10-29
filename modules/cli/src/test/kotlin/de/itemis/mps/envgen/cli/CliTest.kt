package de.itemis.mps.envgen.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.sun.net.httpserver.HttpServer
import de.itemis.mps.envgen.FakeNexus
import de.itemis.mps.envgen.OS
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
        )

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

        val dist = File(target, Platform.distributionDirName(MpsVersion.parse("2026.1"), platform.os))
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
        assertNotEquals(first.getProperty("httpPort"), second.getProperty("httpPort"))
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
        assertNotEquals(props(env("2026.1")).getProperty("httpPort"), props(copy).getProperty("httpPort"))
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
        File(target, Platform.distributionDirName(MpsVersion.parse("2026.1"), platform.os)).deleteRecursively()

        run("refresh", moved.path)
        assertEquals("user", laf.let { File(moved, "mps/config/options/laf.xml") }.readText())
        assertTrue(moved.path in File(moved, "mps/idea.properties").readText())
        assertTrue(script("b", moved).isFile)
        // the distribution is prepared next to the environment again (download cache reused)
        assertTrue(File(moved.parentFile, Platform.distributionDirName(MpsVersion.parse("2026.1"), platform.os)).isDirectory)
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
    fun `prints completion scripts`() {
        val e = assertThrows<CliktError> { MpsEnvGenCommand(services).parse(listOf("completion", "bash")) }
        assertTrue("complete" in e.message!!)
    }

    @Test
    fun `validates arguments`() {
        assertThrows<CliktError> { run("create", "not-a-version") }
        assertThrows<CliktError> { run("create", "2026.1", "--name", "mps-2026.1-LINUX") }
        assertThrows<CliktError> { run("create", "2025.2.1") }
        assertThrows<CliktError> { run("refresh", "missing") }
    }
}
