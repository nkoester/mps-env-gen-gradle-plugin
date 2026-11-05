package de.itemis.mps.envgen.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private val env get() = File(projectDir, ".mpsconfig/default")
    private val plugins get() = File(env, "mps/plugins")

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(*args, "--configuration-cache", "--stacktrace")

    private fun descriptor(id: String, since: String = "251") =
        "<idea-plugin><id>$id</id><idea-version since-build=\"$since\"/></idea-plugin>"

    private fun zip(file: File, folder: String, id: String) = file.apply {
        parentFile.mkdirs()
        ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry("$folder/META-INF/plugin.xml")); it.write(descriptor(id).toByteArray()) }
    }

    @BeforeEach
    fun setup() {
        File(projectDir, "mps").mkdirs()
        File(projectDir, "mps/build.txt").writeText("MPS-251.28774.766")
        File(projectDir, "mps-project").mkdirs()
        File(projectDir, "settings.gradle.kts").writeText("")
        zip(File(projectDir, "tools/my-tool.zip"), "my-tool", "com.example.tool")
        File(projectDir, "tools/folder-plugin/META-INF").mkdirs()
        File(projectDir, "tools/folder-plugin/META-INF/plugin.xml").writeText(descriptor("com.example.folder"))
        File(projectDir, "config/mps-plugins.properties").apply { parentFile.mkdirs() }
            .writeText("folder=../tools/folder-plugin\nfolder.property.folder.key=1\n")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { id("de.itemis.mps.envgen") }

            mpsEnvironments {
                mpsPath.set(file("mps"))
                projectPath.set(file("mps-project"))
                pluginsFile.set(file("config/mps-plugins.properties"))
                environments {
                    register("default") {
                        plugins {
                            register("my-tool") {
                                from("tools/my-tool.zip")
                                ports.put("server.port", 8600)
                            }
                        }
                    }
                }
            }
            """.trimIndent()
        )
    }

    @Test
    fun `installs plugins from the plugin file and the DSL, reuses the configuration cache and follows changes`() {
        val first = runner("generateMpsEnvironment-Default").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateMpsEnvironment-Default")?.outcome)
        assertTrue(File(plugins, "my-tool/META-INF/plugin.xml").isFile)
        assertTrue(File(plugins, "folder-plugin/META-INF/plugin.xml").isFile)
        val vmoptions = File(env, "mps/mps64.vmoptions").readLines()
        assertTrue("-Dfolder.key=1" in vmoptions && "-Dserver.port=8600" in vmoptions, vmoptions.toString())
        val props = Properties().apply { File(env, "environment.properties").reader().use { load(it) } }
        assertEquals("folder,my-tool", props.getProperty("plugins"))
        val script = env.listFiles()!!.single { it.name.startsWith("startMPS_") }
        assertTrue("./gradlew generateMpsEnvironment-Default" in script.readText(), "restore hint in $script")

        val second = runner("generateMpsEnvironment-Default").build()
        assertTrue("Configuration cache entry reused" in second.output, second.output)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateMpsEnvironment-Default")?.outcome)

        // a changed plugin file invalidates the configuration cache; the removed plugin goes
        File(projectDir, "config/mps-plugins.properties").writeText("")
        val third = runner("generateMpsEnvironment-Default").build()
        assertFalse("Configuration cache entry reused" in third.output, third.output)
        assertFalse(File(plugins, "folder-plugin").exists())
        assertTrue(File(plugins, "my-tool").isDirectory)

        // a replaced zip runs the task again and installs the new content
        zip(File(projectDir, "tools/my-tool.zip"), "my-tool-2", "com.example.tool")
        val fourth = runner("generateMpsEnvironment-Default").build()
        assertEquals(TaskOutcome.SUCCESS, fourth.task(":generateMpsEnvironment-Default")?.outcome)
        assertTrue(File(plugins, "my-tool-2").isDirectory)
        assertFalse(File(plugins, "my-tool").exists())
    }

    @Test
    fun `incompatible plugins fail the build with the reason`() {
        zip(File(projectDir, "tools/my-tool.zip"), "my-tool", "com.example.tool").also {
            ZipOutputStream(it.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("my-tool/META-INF/plugin.xml")); zip.write(descriptor("com.example.tool", since = "261").toByteArray())
            }
        }
        val result = runner("generateMpsEnvironment-Default").buildAndFail()
        assertTrue("requires build 261 or later (2026.1)" in result.output, result.output)
        // Gradle creates the output folder before the task runs; nothing is written into it
        assertEquals(emptyList(), env.walkTopDown().filter { it.isFile }.toList())
    }
}
