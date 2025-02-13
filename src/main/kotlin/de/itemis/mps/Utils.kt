package de.itemis.mps

import java.util.*

// Use OsUtils instead?
object Utils {
    var currentOS: OS = run {
        with(System.getProperty("os.name").lowercase(Locale.ENGLISH)) {
            when {
                contains("win") -> OS.WINDOWS
                contains("nix") || contains("nux") || contains("aix") -> OS.LINUX
                contains("mac") -> OS.MAC
                else -> OS.OTHER
            }
        }
    }

    public enum class OS {
        WINDOWS, LINUX, MAC, OTHER
    }
}