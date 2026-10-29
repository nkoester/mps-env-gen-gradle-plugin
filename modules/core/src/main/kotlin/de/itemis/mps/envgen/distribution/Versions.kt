package de.itemis.mps.envgen.distribution

/** MPS version as published on Nexus, e.g. `2022.2`, `2024.1.5`, `2026.1-RC1` */
data class MpsVersion(val year: Int, val minor: Int, val patch: Int, val qualifier: String?) : Comparable<MpsVersion> {

    val isPreRelease: Boolean get() = qualifier != null

    /** pre-releases sort before the release with the same numbers: EAP < RC < release */
    override fun compareTo(other: MpsVersion): Int =
        compareValuesBy(
            this, other,
            { it.year }, { it.minor }, { it.patch }, { it.qualifierRank }, { it.qualifierNumber }, { it.qualifier.orEmpty() },
        )

    private val qualifierRank: Int
        get() = when {
            qualifier == null -> 2
            qualifier.startsWith("RC", ignoreCase = true) -> 1
            else -> 0
        }

    private val qualifierNumber: Int
        get() = qualifier?.takeLastWhile { it.isDigit() }?.toIntOrNull() ?: 0

    override fun toString(): String = buildString {
        append("$year.$minor")
        if (patch > 0) append(".$patch")
        qualifier?.let { append("-$it") }
    }

    companion object {
        private val PATTERN = Regex("""^(\d{4})\.(\d+)(?:\.(\d+))?(?:-([A-Za-z0-9.]+))?$""")

        val FIRST_SUPPORTED = parse("2022.2")

        fun parse(version: String): MpsVersion {
            val m = requireNotNull(PATTERN.matchEntire(version.trim())) {
                "Invalid MPS version '$version'. Expected e.g. 2024.1, 2024.1.5 or 2026.1-RC1"
            }
            val (year, minor, patch, qualifier) = m.destructured
            return MpsVersion(year.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt(), qualifier.ifEmpty { null })
        }
    }
}

/**
 * JBR version in Nexus notation `<feature>-b<build>`, e.g. `21.0.8-b1163.69`.
 * Accepts the notations used by mbeddr/build.publish.jdk and `mps.runtimeBuild`:
 * `21.0.8b1163.69`, `21.0.8-b1163.69`, `21.0.8+9-b1163.69` (the `+9` is dropped).
 */
data class JbrVersion(val feature: String, val build: String) : Comparable<JbrVersion> {

    /** builds of one line (same first build number) share the platform base, e.g. `b1000` */
    val buildLine: String get() = "b" + build.substringBefore('.')

    private val buildNumbers: List<Int> get() = build.split('.').map { it.toInt() }
    private val featureNumbers: List<Int> get() = feature.split('.').map { it.toInt() }

    override fun compareTo(other: JbrVersion): Int {
        compareLists(buildNumbers, other.buildNumbers).let { if (it != 0) return it }
        return compareLists(featureNumbers, other.featureNumbers)
    }

    override fun toString(): String = "$feature-b$build"

    companion object {
        private val PATTERN = Regex("""^(\d+(?:\.\d+)*)(?:\+\d+)?-?b(\d+(?:\.\d+)*)$""")

        fun parse(version: String): JbrVersion = requireNotNull(parseOrNull(version)) {
            "Invalid JBR version '$version'. Expected e.g. 21.0.8-b1163.69"
        }

        fun parseOrNull(version: String): JbrVersion? =
            PATTERN.matchEntire(version.trim())?.destructured?.let { (feature, build) -> JbrVersion(feature, build) }

        private fun compareLists(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
