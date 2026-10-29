package de.itemis.mps.envgen.environment

import java.io.File

/** build of an MPS installation from its `build.txt`, e.g. `MPS-251.23774.423` */
data class MpsBuild(val number: String) {

    /** IntelliJ platform baseline, e.g. 251 for MPS 2025.1 */
    val baseline: Int get() = number.substringBefore('.').toIntOrNull() ?: 0

    /** the new UI is the default from platform 242 on (IntelliJ 2024.2; MPS 2024.3) */
    val newUi: Boolean get() = baseline >= NEW_UI_BASELINE

    override fun toString() = "MPS-$number"

    companion object {
        const val NEW_UI_BASELINE = 242

        /** null if the installation has no readable `build.txt` */
        fun of(mpsHome: File): MpsBuild? =
            listOf("build.txt", "Contents/build.txt", "Contents/Resources/build.txt")
                .map { File(mpsHome, it) }.firstOrNull { it.isFile }
                ?.readText()?.trim()?.removePrefix("MPS-")
                // MPS-<platform baseline>.<build>[.<fix>], e.g. MPS-251.23774.423
                ?.takeIf { Regex("""\d{3}\.\d+(\.\d+)*""").matches(it) }
                ?.let(::MpsBuild)
    }
}
