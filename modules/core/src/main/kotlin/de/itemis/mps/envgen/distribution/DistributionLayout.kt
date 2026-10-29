package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import java.io.File

data class Launcher(val kind: Kind, val path: String) {
    enum class Kind { NATIVE, SCRIPT }
}

data class ValidationResult(val errors: List<String>, val warnings: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Turns an unpacked generic MPS zip (as published on Nexus) plus an unpacked JBR into a runnable MPS.
 * Operates on directories only; unpacking is up to the caller.
 */
object DistributionLayout {

    /** extensions in `bin/` or `MacOS/` that stay non-executable; everything else gets the bit the Nexus zip drops */
    private val NON_EXECUTABLE_EXTENSIONS = setOf(
        "vmoptions", "properties", "policy", "svg", "png", "icns", "txt", "xml", "json", "dll", "dylib", "so", "jnilib", "plist", "ps1"
    )

    /** folder names that belong to the content itself and are never stripped as archive root */
    val MPS_CONTENT = setOf("bin", "lib", "plugins", "languages", "license")
    val JBR_CONTENT = setOf("bin", "lib", "conf", "Contents", "legal")

    /**
     * Moves the content of a single top-level directory one level up; archives without one (e.g. Nexus MPS zips)
     * or whose single directory is part of the content ([contentNames]) are left untouched.
     */
    fun stripSingleTopLevelDirectory(dir: File, contentNames: Set<String>) {
        val entries = dir.listFiles().orEmpty()
        val root = entries.singleOrNull()?.takeIf { it.isDirectory && it.name !in contentNames } ?: return
        val tmp = File(dir, ".strip-${System.nanoTime()}")
        check(root.renameTo(tmp)) { "Unable to move $root" }
        tmp.listFiles().orEmpty().forEach { child ->
            check(child.renameTo(File(dir, child.name))) { "Unable to move $child" }
        }
        check(tmp.delete()) { "Unable to delete $tmp" }
    }

    /**
     * Applies the OS specific layout of the generic zip in place: files of `bin/<os>` and `bin/<os>/<arch>` into `bin`,
     * files of `lib/jna/<arch>` into `lib/jna`, and the macOS bundle skeleton (`bin/mac/Contents`) into the MPS home.
     *
     * @param home MPS home, i.e. `distributionRoot/platform.homePath`
     */
    fun applyLayout(home: File, platform: Platform) {
        val bin = File(home, "bin")
        overlayFiles(File(bin, platform.distFolder), bin)
        overlayFiles(File(bin, "${platform.distFolder}/${platform.arch.mpsName}"), bin)
        overlayFiles(File(home, "lib/jna/${platform.arch.mpsName}"), File(home, "lib/jna"))

        val bundleSkeleton = File(bin, "${platform.distFolder}/Contents")
        if (platform.os == OS.MAC && bundleSkeleton.isDirectory) {
            bundleSkeleton.listFiles().orEmpty().forEach { it.copyRecursively(File(home, it.name), overwrite = true) }
            val archLauncher = File(home, "MacOS/mps-${platform.arch.macLauncherSuffix}")
            if (archLauncher.isFile) archLauncher.copyTo(File(home, platform.nativeLauncher), overwrite = true)
        }
    }

    /** sets the executable bit on launchers and native helpers in `bin/` and `MacOS/`; no-op on Windows file systems */
    fun setExecutableBits(home: File) {
        listOf("bin", "MacOS").map { File(home, it) }.filter { it.isDirectory }.forEach { dir ->
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() !in NON_EXECUTABLE_EXTENSIONS }
                .forEach { it.setExecutable(true, false) }
        }
    }

    fun javaHome(home: File, platform: Platform): File = File(home, "${Platform.JBR_DIR}/${platform.javaHome}").normalize()

    fun validate(home: File, platform: Platform): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!File(home, platform.scriptLauncher).isFile) errors += "Launcher ${platform.scriptLauncher} is missing"
        val java = File(javaHome(home, platform), platform.javaExecutable)
        if (!java.isFile) errors += "Java executable ${java.relativeTo(home)} is missing"

        // MPS 2022.2 ships no lib/jna; JNA then extracts its native library from the jar
        val jna = File(home, "lib/jna")
        if (jna.listFiles().orEmpty().none { it.isFile && "jnidispatch" in it.name }) {
            warnings += "No JNA native library in lib/jna; JNA falls back to the library inside its jar"
        }
        return ValidationResult(errors, warnings)
    }

    /**
     * Native launcher if the binary exists and `product-info.json` has a launch record for the platform;
     * otherwise the script launcher. The descriptor is never modified.
     */
    fun selectLauncher(home: File, platform: Platform): Launcher {
        val native = File(home, platform.nativeLauncher)
        val descriptor = File(home, platform.productInfo)
        if (native.isFile && descriptor.isFile && hasLaunchRecord(descriptor, platform)) {
            return Launcher(Launcher.Kind.NATIVE, platform.nativeLauncher)
        }
        return Launcher(Launcher.Kind.SCRIPT, platform.scriptLauncher)
    }

    private fun hasLaunchRecord(descriptor: File, platform: Platform): Boolean {
        val json = runCatching { MiniJson.parse(descriptor.readText()) as? Map<*, *> }.getOrNull() ?: return false
        val records = json["launch"] as? List<*> ?: return false
        // the native launcher rejects descriptors with more or less than one launch record
        val record = records.singleOrNull() as? Map<*, *> ?: return false
        return (record["os"] as? String).equals(platform.productInfoOs, ignoreCase = true) &&
            record["arch"] == platform.arch.mpsName
    }

    /** copies the files (not subdirectories) of [source] into [target] */
    private fun overlayFiles(source: File, target: File) {
        if (!source.isDirectory) return
        source.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(target, it.name), overwrite = true) }
    }
}
