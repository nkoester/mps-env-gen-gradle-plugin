package de.itemis.mps.envgen.environment

import java.io.File

/** build of an MPS installation from its `build.txt`, e.g. `MPS-251.23774.423` */
data class MpsBuild(val number: String) {

    /** IntelliJ platform baseline, e.g. 251 for MPS 2025.1 */
    val baseline: Int get() = number.substringBefore('.').toIntOrNull() ?: 0

    /** the new UI is the default from platform 242 on (IntelliJ 2024.2; MPS 2024.3) */
    val newUi: Boolean get() = baseline >= NEW_UI_BASELINE

    /** release series, e.g. 2025.1 for baseline 251 */
    val series: String get() = seriesOf(baseline)

    override fun toString() = "MPS-$number"

    companion object {
        const val NEW_UI_BASELINE = 242

        /** baseline `YYN` -> series `20YY.N`, e.g. 261 -> 2026.1 */
        fun seriesOf(baseline: Int): String = "${2000 + baseline / 10}.${baseline % 10}"

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
