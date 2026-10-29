package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import java.util.Locale

enum class Arch(
    /** folder name used by MPS for arch specific files (`bin/mac/<arch>`, `lib/jna/<arch>`) and in `product-info.json` */
    val mpsName: String,
    /** arch part of the JBR classifier */
    val jbrName: String,
    /** suffix of the macOS native launcher in the generic zip (`bin/mac/Contents/MacOS/mps-<suffix>`) */
    val macLauncherSuffix: String,
) {
    AMD64("amd64", "x64", "x64"),
    AARCH64("aarch64", "aarch64", "aarch64");

    companion object {
        val current: Arch
            get() = of(System.getProperty("os.arch"))

        fun of(osArch: String): Arch = when (osArch.lowercase(Locale.ENGLISH)) {
            "amd64", "x86_64", "x64" -> AMD64
            "aarch64", "arm64" -> AARCH64
            else -> throw IllegalArgumentException("Unsupported architecture '$osArch'. Supported: amd64, aarch64")
        }
    }
}

/**
 * All OS specific knowledge needed to assemble and launch a runnable MPS. Paths are relative to the MPS home,
 * except [homePath] (relative to the distribution root) and [javaHome] (relative to the JBR root).
 */
data class Platform(
    val os: OS,
    val arch: Arch,
    /** OS folder in the generic zip: `bin/<distFolder>` */
    val distFolder: String,
    /** MPS home inside the distribution root; macOS needs an app bundle layout for its native launcher */
    val homePath: String,
    val scriptLauncher: String,
    val nativeLauncher: String,
    /** location of the descriptor the native launcher reads */
    val productInfo: String,
    /** value of `launch[].os` in `product-info.json` */
    val productInfoOs: String,
    val javaHome: String,
    val javaExecutable: String,
) {
    val jbrClassifier: String get() = "$jbrOs-${arch.jbrName}"

    private val jbrOs: String
        get() = when (os) {
            OS.LINUX -> "linux"
            OS.MAC -> "osx"
            else -> "windows"
        }

    companion object {
        const val JBR_DIR = "jbr"

        val current: Platform
            get() = of(OS.current, Arch.current)

        fun of(os: OS, arch: Arch): Platform = when (os) {
            OS.LINUX -> Platform(
                os, arch,
                distFolder = "linux",
                homePath = ".",
                scriptLauncher = "bin/mps.sh",
                nativeLauncher = "bin/mps",
                productInfo = "product-info.json",
                productInfoOs = "Linux",
                javaHome = ".",
                javaExecutable = "bin/java",
            )

            OS.WINDOWS -> Platform(
                os, arch,
                distFolder = "win",
                homePath = ".",
                scriptLauncher = "bin/mps.bat",
                nativeLauncher = "bin/mps64.exe",
                productInfo = "product-info.json",
                productInfoOs = "Windows",
                javaHome = ".",
                javaExecutable = "bin/java.exe",
            )

            OS.MAC -> Platform(
                os, arch,
                distFolder = "mac",
                homePath = "MPS.app/Contents",
                scriptLauncher = "bin/mps.sh",
                nativeLauncher = "MacOS/mps",
                productInfo = "Resources/product-info.json",
                productInfoOs = "macOS",
                javaHome = "Contents/Home",
                javaExecutable = "bin/java",
            )

            OS.OTHER -> throw IllegalArgumentException("Unsupported operating system. Supported: Linux, Windows, macOS")
        }

        /** prefix of prepared distributions inside the target path */
        const val DISTRIBUTION_DIR_PREFIX = "mps-"

        private val DISTRIBUTION_DIR = Regex("""mps-\d{4}\.\d+.*-(LINUX|MAC|WINDOWS)""")

        /** folder name of a prepared distribution inside the target path, e.g. `mps-2025.1.4-LINUX` */
        fun distributionDirName(mpsVersion: MpsVersion, os: OS): String = "$DISTRIBUTION_DIR_PREFIX$mpsVersion-${os.name}"

        /** names of prepared distributions (`mps-<version>-<OS>`), which no environment may use */
        fun isReservedEnvironmentName(name: String): Boolean = DISTRIBUTION_DIR.matches(name)
    }
}
