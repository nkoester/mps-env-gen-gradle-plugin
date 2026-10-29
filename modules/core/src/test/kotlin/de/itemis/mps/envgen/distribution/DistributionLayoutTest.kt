package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS as JUnitOS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** fake distributions follow the layouts of the Nexus zips of MPS 2022.2.4 and 2026.1 */
class DistributionLayoutTest {

    @TempDir
    lateinit var tmp: File

    private fun tree(root: File, vararg paths: String): File {
        paths.forEach { File(root, it).apply { parentFile.mkdirs() }.writeText(it) }
        return root
    }

    private fun productInfo(os: String, arch: String = "amd64") =
        """{"name":"MPS","launch":[{"os":"$os","arch":"$arch","launcherPath":"bin/mps","javaExecutablePath":"jbr/bin/java"}]}"""

    /** generic zip 2022.2.x: no native launchers, no lib/jna, no product-info.json */
    private fun generic2022(home: File) = tree(
        home,
        "bin/mps.sh", "bin/mps64.vmoptions",
        "bin/linux/fsnotifier", "bin/linux/restart.py", "bin/linux/libdbm.so",
        "bin/win/mps.bat", "bin/win/fsnotifier.exe",
        "bin/mac/fsnotifier", "bin/mac/restarter", "bin/mac/libnst64.dylib",
        "lib/app.jar",
    )

    /** generic zip 2026.1: native launchers, arch folders, mac bundle skeleton, Linux-only launch record */
    private fun generic2026(home: File) = tree(
        home,
        "bin/mps.sh", "bin/mps64.vmoptions",
        "bin/linux/mps", "bin/linux/fsnotifier", "bin/linux/restarter",
        "bin/win/mps.bat", "bin/win/mps64.exe", "bin/win/fsnotifier.exe",
        "bin/mac/fsnotifier", "bin/mac/aarch64/restarter", "bin/mac/amd64/restarter",
        "bin/mac/Contents/MacOS/mps-x64", "bin/mac/Contents/MacOS/mps-aarch64",
        "bin/mac/Contents/Info.plist", "bin/mac/Contents/Resources/mps.icns",
        "lib/jna/amd64/libjnidispatch.so", "lib/jna/aarch64/libjnidispatch.so", "lib/app.jar",
    ).also { File(it, "product-info.json").writeText(productInfo("Linux")) }

    private fun jbr(home: File, platform: Platform) =
        tree(File(home, "${Platform.JBR_DIR}/${platform.javaHome}"), platform.javaExecutable)

    @Test
    fun `strips a single top-level directory`() {
        val dir = tree(File(tmp, "x"), "MPS 2022.2/bin/mps.sh", "MPS 2022.2/lib/app.jar")
        DistributionLayout.stripSingleTopLevelDirectory(dir, DistributionLayout.MPS_CONTENT)
        assertEquals(setOf("bin", "lib"), dir.list()!!.toSet())
        assertTrue(File(dir, "bin/mps.sh").isFile)
    }

    @Test
    fun `keeps archives without a single top-level directory`() {
        // Nexus zips have no top folder
        val dir = tree(File(tmp, "x"), "bin/mps.sh", "build.txt")
        DistributionLayout.stripSingleTopLevelDirectory(dir, DistributionLayout.MPS_CONTENT)
        assertEquals(setOf("bin", "build.txt"), dir.list()!!.toSet())
    }

    @Test
    fun `never strips a content folder`() {
        val dir = tree(File(tmp, "x"), "bin/mps.sh", "bin/linux/fsnotifier")
        DistributionLayout.stripSingleTopLevelDirectory(dir, DistributionLayout.MPS_CONTENT)
        assertEquals(setOf("bin"), dir.list()!!.toSet())
    }

    @ParameterizedTest
    @EnumSource(Arch::class)
    fun `linux 2026 uses native launcher on amd64 only`(arch: Arch) {
        val platform = Platform.of(OS.LINUX, arch)
        val home = generic2026(File(tmp, "home"))
        DistributionLayout.applyLayout(home, platform)
        jbr(home, platform)

        assertTrue(File(home, "bin/mps").isFile)
        assertTrue(File(home, "bin/restarter").isFile)
        assertEquals("lib/jna/${arch.mpsName}/libjnidispatch.so", File(home, "lib/jna/libjnidispatch.so").readText())
        assertTrue(DistributionLayout.validate(home, platform).let { it.isValid && it.warnings.isEmpty() })

        val expected = if (arch == Arch.AMD64) Launcher(Launcher.Kind.NATIVE, "bin/mps") else Launcher(Launcher.Kind.SCRIPT, "bin/mps.sh")
        assertEquals(expected, DistributionLayout.selectLauncher(home, platform))
    }

    @Test
    fun `windows 2026 falls back to script launcher because of the Linux launch record`() {
        val platform = Platform.of(OS.WINDOWS, Arch.AMD64)
        val home = generic2026(File(tmp, "home"))
        DistributionLayout.applyLayout(home, platform)
        jbr(home, platform)

        assertTrue(File(home, "bin/mps.bat").isFile)
        assertTrue(File(home, "bin/mps64.exe").isFile)
        assertTrue(DistributionLayout.validate(home, platform).isValid)
        assertEquals(Launcher(Launcher.Kind.SCRIPT, "bin/mps.bat"), DistributionLayout.selectLauncher(home, platform))
    }

    @ParameterizedTest
    @EnumSource(Arch::class)
    fun `mac 2026 assembles bundle skeleton with arch specific files`(arch: Arch) {
        val platform = Platform.of(OS.MAC, arch)
        val home = generic2026(File(File(tmp, "dist"), platform.homePath))
        DistributionLayout.applyLayout(home, platform)
        jbr(home, platform)

        assertEquals("bin/mac/Contents/MacOS/mps-${arch.macLauncherSuffix}", File(home, "MacOS/mps").readText())
        assertEquals("bin/mac/${arch.mpsName}/restarter", File(home, "bin/restarter").readText())
        assertTrue(File(home, "Info.plist").isFile)
        assertTrue(File(home, "jbr/Contents/Home/bin/java").isFile)
        assertTrue(DistributionLayout.validate(home, platform).isValid)
        // descriptor of the generic zip is neither at Resources/ nor for macOS
        assertEquals(Launcher(Launcher.Kind.SCRIPT, "bin/mps.sh"), DistributionLayout.selectLauncher(home, platform))
    }

    @Test
    fun `mac native distribution uses native launcher`() {
        val platform = Platform.of(OS.MAC, Arch.AARCH64)
        val home = tree(File(tmp, "MPS.app/Contents"), "MacOS/mps", "bin/mps.sh")
        File(home, "Resources/product-info.json").apply { parentFile.mkdirs() }.writeText(productInfo("macOS", "aarch64"))
        assertEquals(Launcher(Launcher.Kind.NATIVE, "MacOS/mps"), DistributionLayout.selectLauncher(home, platform))
    }

    @Test
    fun `2022 uses script launcher and warns about missing jna`() {
        val platform = Platform.of(OS.LINUX, Arch.AMD64)
        val home = generic2022(File(tmp, "home"))
        DistributionLayout.applyLayout(home, platform)
        jbr(home, platform)

        assertTrue(File(home, "bin/restart.py").isFile)
        val result = DistributionLayout.validate(home, platform)
        assertTrue(result.isValid)
        assertEquals(1, result.warnings.size)
        assertEquals(Launcher(Launcher.Kind.SCRIPT, "bin/mps.sh"), DistributionLayout.selectLauncher(home, platform))
    }

    @Test
    fun `validation reports missing launcher and java`() {
        val platform = Platform.of(OS.LINUX, Arch.AMD64)
        val result = DistributionLayout.validate(tree(File(tmp, "home"), "lib/app.jar"), platform)
        assertFalse(result.isValid)
        assertEquals(2, result.errors.size)
    }

    @Test
    fun `ignores malformed or multi record descriptors`() {
        val platform = Platform.of(OS.LINUX, Arch.AMD64)
        val home = tree(File(tmp, "home"), "bin/mps", "bin/mps.sh")
        val descriptor = File(home, "product-info.json")
        descriptor.writeText("{ not json")
        assertEquals(Launcher.Kind.SCRIPT, DistributionLayout.selectLauncher(home, platform).kind)
        descriptor.writeText("""{"launch":[{"os":"Linux","arch":"amd64"},{"os":"Linux","arch":"aarch64"}]}""")
        assertEquals(Launcher.Kind.SCRIPT, DistributionLayout.selectLauncher(home, platform).kind)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `sets executable bits on launchers and helpers only`() {
        val platform = Platform.of(OS.LINUX, Arch.AMD64)
        val home = generic2026(File(tmp, "home"))
        DistributionLayout.applyLayout(home, platform)
        DistributionLayout.setExecutableBits(home)

        listOf("bin/mps.sh", "bin/mps", "bin/fsnotifier", "bin/restarter").forEach { assertTrue(File(home, it).canExecute(), it) }
        listOf("bin/mps64.vmoptions", "lib/app.jar").forEach { assertFalse(File(home, it).canExecute(), it) }
    }

    @Test
    fun `distribution dir name`() {
        assertEquals("mps-2025.3-LINUX", Platform.distributionDirName(MpsVersion.parse("2025.3"), OS.LINUX))
        assertEquals("mps-2026.1-RC1-MAC", Platform.distributionDirName(MpsVersion.parse("2026.1-RC1"), OS.MAC))
    }

    @Test
    fun `jbr classifiers match Nexus`() {
        assertEquals("linux-x64", Platform.of(OS.LINUX, Arch.AMD64).jbrClassifier)
        assertEquals("osx-aarch64", Platform.of(OS.MAC, Arch.AARCH64).jbrClassifier)
        assertEquals("windows-aarch64", Platform.of(OS.WINDOWS, Arch.AARCH64).jbrClassifier)
    }
}
