package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.Arch
import de.itemis.mps.envgen.distribution.Platform
import de.itemis.mps.envgen.environment.EnvironmentGenerator
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS as JUnitOS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** generates an environment and runs its start script against a fake MPS that prints what it received */
@DisabledOnOs(JUnitOS.WINDOWS)
class StartScriptTest {

    @TempDir
    lateinit var projectDir: File

    private val platform = Platform.current

    // shell metacharacters must survive generation and quoting
    private val mpsDir: File get() = File(projectDir, "my mps \$HOME `x` \"q\"")
    private val jbrDir: File get() = File(projectDir, "jbr")

    private fun fakeLauncher(relative: String, name: String) =
        File(mpsDir, relative).apply {
            parentFile.mkdirs()
            // IDE_JVM_ARGS split as mps.sh does before calling java: IFS is newline and tab, not space
            writeText(
                "#!/bin/sh\necho \"LAUNCHED $name\"\nenv | grep -E '^(MPS_|IDEA_VM)' | sort\n" +
                    "IFS=\"\$(printf '\\n\\t')\"\nfor arg in \${IDE_JVM_ARGS-}; do echo \"JVM_ARG[\${arg}]\"; done\n"
            )
            setExecutable(true)
        }

    private fun generate(os: OS = platform.os): File {
        File(projectDir, "settings.gradle.kts").writeText("")
        File(projectDir, "mps-project/.mps").mkdirs()
        jbrDir.mkdirs()
        mpsDir.mkdirs()
        // the path contains '$', which a Kotlin build script would interpolate
        File(projectDir, "mps-path.txt").writeText(mpsDir.path)
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { id("de.itemis.mps.envgen") }
            mpsEnvironments {
                mpsPath.set(file(file("mps-path.txt").readText()))
                javaHome.set(layout.projectDirectory.dir("jbr"))
                projectPath.set(layout.projectDirectory.dir("mps-project"))
                environments.register("Default") { os.set(listOf(de.itemis.mps.envgen.OS.${os.name})) }
            }
            """.trimIndent()
        )
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments("generateMpsEnvironment-Default").build()
        return File(projectDir, ".mpsconfig/Default/" + EnvironmentGenerator.startScriptName(os, "Default"))
    }

    private data class Run(val exitCode: Int, val output: String)

    private fun run(script: File, environment: Map<String, String> = emptyMap()): Run {
        val builder = ProcessBuilder(script.path).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        builder.environment().remove("IDE_JVM_ARGS")
        builder.environment().putAll(environment)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS))
        return Run(process.exitValue(), output)
    }

    @Test
    fun `exports the environment for both launcher kinds`() {
        fakeLauncher(platform.scriptLauncher, "script")
        val result = run(generate())
        assertEquals(0, result.exitCode, result.output)

        val env = File(projectDir, ".mpsconfig/Default/mps").path
        assertTrue("LAUNCHED script" in result.output, result.output)
        assertTrue("MPS_PROPERTIES=$env/idea.properties" in result.output, result.output)
        assertTrue("IDEA_VM_OPTIONS=$env/mps64.vmoptions" in result.output, result.output)
        assertTrue("MPS_VM_OPTIONS=$env/mps64.vmoptions" in result.output, result.output)
        assertTrue("MPS_JDK=${jbrDir.path}" in result.output, result.output)
    }

    @Test
    fun `uses the native launcher if the descriptor matches`() {
        if (platform.os != OS.LINUX) return
        fakeLauncher(platform.scriptLauncher, "script")
        fakeLauncher(platform.nativeLauncher, "native")
        File(mpsDir, "product-info.json").writeText(
            """{"launch":[{"os":"Linux","arch":"${platform.arch.mpsName}","launcherPath":"bin/mps"}]}"""
        )
        val result = run(generate())
        assertTrue("LAUNCHED native" in result.output, result.output)
    }

    // the macOS script is POSIX sh, so it runs on Linux as well; MPS home is the MPS path itself (no Contents/bin)
    private val macOpens = listOf("sun.lwawt", "sun.lwawt.macosx", "com.apple.laf", "com.apple.eawt", "com.apple.eawt.event")
        .map { "--add-opens=java.desktop/$it=ALL-UNNAMED" }

    @Test
    fun `macOS script launcher gets the macOS packages opened`() {
        fakeLauncher("bin/mps.sh", "script")
        val result = run(generate(OS.MAC), mapOf("IDE_JVM_ARGS" to "-Dfrom.user=1"))
        assertEquals(0, result.exitCode, result.output)
        assertTrue("LAUNCHED script" in result.output, result.output)
        // java gets every option as an argument of its own, a value set by the user first
        val args = Regex("""JVM_ARG\[(.*)]""").findAll(result.output).map { it.groupValues[1] }.toList()
        assertEquals(listOf("-Dfrom.user=1") + macOpens, args, result.output)
    }

    @Test
    fun `macOS native launcher gets no extra packages`() {
        fakeLauncher("bin/mps.sh", "script")
        fakeLauncher("MacOS/mps", "native")
        File(mpsDir, "Resources/product-info.json").apply { parentFile.mkdirs() }.writeText(
            """{"launch":[{"os":"macOS","arch":"${platform.arch.mpsName}","launcherPath":"../MacOS/mps"}]}"""
        )
        val result = run(generate(OS.MAC))
        assertEquals(0, result.exitCode, result.output)
        assertTrue("LAUNCHED native" in result.output, result.output)
        assertTrue("IDE_JVM_ARGS" !in result.output && "JVM_ARG[" !in result.output, result.output)
    }

    @Test
    fun `explains a missing MPS installation`() {
        val script = generate()
        val result = run(script)
        assertEquals(1, result.exitCode, result.output)
        assertTrue("MPS not found" in result.output && "Check 'mpsPath'" in result.output, result.output)
    }

    @Test
    fun `warns about a moved environment and continues without terminal`() {
        fakeLauncher(platform.scriptLauncher, "script")
        val script = generate()
        val moved = File(projectDir, "moved").also { script.parentFile.copyRecursively(it) }
        File(moved, script.name).setExecutable(true)

        val result = run(File(moved, script.name))
        assertTrue("generated for" in result.output && "Starting with the paths of the original location" in result.output, result.output)
        assertTrue("LAUNCHED script" in result.output, result.output)
    }

    @Test
    fun `script launcher of the current platform`() {
        // guards the assumption the fake launchers above rely on
        assertEquals("bin/mps.sh", Platform.of(platform.os, Arch.AMD64).scriptLauncher)
    }
}
