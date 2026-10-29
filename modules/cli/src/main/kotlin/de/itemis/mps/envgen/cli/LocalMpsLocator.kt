package de.itemis.mps.envgen.cli

import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.distribution.MpsVersion
import java.io.File

/** finds installed MPS below a folder of installations like `/vol/mps`; folder names are free-form */
object LocalMpsLocator {

    /** `<installs>/<name>` if it is an MPS installation, e.g. `MPS-2020.3.5-linux` */
    fun byName(installs: File, name: String): File? = File(installs, name).takeIf(::isMpsHome)

    /**
     * an installation whose folder name contains [version] as a whole (`2024.1` does not match `MPS-2024.1.5`);
     * prefers names equal to the version or `MPS-<version>`, then names mentioning the OS
     */
    fun find(installs: File, version: MpsVersion, os: OS): File? {
        val v = Regex.escape(version.toString())
        val pattern = Regex("(^|[^0-9.])$v([^0-9.]|$)", RegexOption.IGNORE_CASE)
        val osHint = when (os) {
            OS.LINUX -> "linux"
            OS.MAC -> "mac"
            OS.WINDOWS -> "win"
            OS.OTHER -> null
        }
        return installs.listFiles().orEmpty()
            .filter { it.isDirectory && pattern.containsMatchIn(it.name) && isMpsHome(it) }
            .minWithOrNull(compareBy<File>(
                { if (it.name.equals("$version", true) || it.name.equals("MPS-$version", true)) 0 else 1 },
                { if (osHint != null && it.name.lowercase().contains(osHint)) 0 else 1 },
                { it.name },
            ))
    }

    fun isMpsHome(dir: File) = File(dir, "bin").isDirectory || File(dir, "Contents/bin").isDirectory
}
