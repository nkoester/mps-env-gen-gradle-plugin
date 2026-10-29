package de.itemis.mps.envgen

import java.io.File

/**
 * the platform's cache directory of mps-env-gen, shared by the CLI and the Gradle plugin: downloads (CLI) and prepared
 * MPS distributions. `MPS_ENV_GEN_CACHE` overrides it; otherwise Linux and others: `$XDG_CACHE_HOME` or `~/.cache`,
 * macOS: `~/Library/Caches`, Windows: `%LOCALAPPDATA%\mps-env-gen\cache`
 */
fun userCacheDir(
    os: OS = OS.current,
    getenv: (String) -> String? = System::getenv,
    home: String = System.getProperty("user.home"),
): File {
    getenv(CACHE_ENV)?.takeIf { it.isNotBlank() }?.let { return File(it) }
    return when (os) {
        OS.MAC -> File(home, "Library/Caches/mps-env-gen")
        OS.WINDOWS -> File(getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Local", "mps-env-gen\\cache")
        else -> File(getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache", "mps-env-gen")
    }
}

/** environment variable overriding [userCacheDir] */
const val CACHE_ENV = "MPS_ENV_GEN_CACHE"

/** prepared MPS distributions (`mps-<version>-<OS>`) inside a cache directory */
fun distributionsDir(cacheDir: File): File = File(cacheDir, "distributions")
