package de.itemis.mps.envgen

import java.util.Locale

enum class OS {
    WINDOWS, LINUX, MAC,
    /** unsupported host */
    OTHER;

    companion object {
        /** operating system of this JVM; static for Groovy build scripts */
        @JvmStatic
        val current: OS = with(System.getProperty("os.name").lowercase(Locale.ENGLISH)) {
            when {
                contains("win") -> WINDOWS
                contains("nix") || contains("nux") || contains("aix") -> LINUX
                contains("mac") -> MAC
                else -> OTHER
            }
        }
    }
}
