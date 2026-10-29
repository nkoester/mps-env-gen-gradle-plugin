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
            writeText("#!/bin/sh\necho \"LAUNCHED $name\"\nenv | grep -E '^(MPS_|IDEA_VM)' | sort\n")
            setExecutable(true)
        }

    private fun generate(): File {
        File(projectDir, "settings.gradle.kts").writeText("")
        File(projectDir, "mps-project/.mps").mkdirs()
        jbrDir.mkdirs()
        mpsDir.mkdirs()
        // the path contains '$', which a Kotlin build script would interpolate
        File(projectDir, "mps-path.txt").writeText(mpsDir.path)
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { id("de.itemis.mps.mps-env-gen-gradle-plugin") }
            mpsEnvironments {
                mpsPath.set(file(file("mps-path.txt").readText()))
                javaHome.set(layout.projectDirectory.dir("jbr"))
                projectPath.set(layout.projectDirectory.dir("mps-project"))
                environments.register("Default")
            }
            """.trimIndent()
        )
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments("generateMpsEnvironment-Default").build()
        return File(projectDir, ".mpsconfig/Default/" + EnvironmentGenerator.startScriptName(platform.os, "Default"))
    }

    private data class Run(val exitCode: Int, val output: String)

    private fun run(script: File): Run {
        val process = ProcessBuilder(script.path).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
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
