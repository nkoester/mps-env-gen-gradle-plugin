package de.itemis.mps.envgen.distribution

class JbrResolutionException(message: String) : RuntimeException(message)

data class JbrResolution(val version: JbrVersion, val source: Source, val warning: String? = null) {
    enum class Source { EXPLICIT, MARKER, BUILT_IN_TABLE, BUILD_LINE_FALLBACK }
}

/**
 * Picks the JBR for an MPS version. Lookups against Nexus are passed in, which keeps this free of Gradle.
 *
 * Order: explicit version > `com.jetbrains.mps:mps-jbr` marker POM > built-in table (MPS versions without marker)
 * > newest published build of the same build line if the wanted build is not published.
 */
object JbrResolver {

    /** bundled JBR per MPS release without marker POM; closed set, these MPS versions get no further releases */
    internal val BUILT_IN = mapOf(
        "2022.2" to "17.0.3-b469.32",
        "2022.2.1" to "17.0.6-b469.82",
        "2022.2.2" to "17.0.6-b469.82",
        "2022.2.3" to "17.0.6-b469.82",
        "2022.2.4" to "17.0.6-b469.82",
        "2022.3" to "17.0.6-b653.34",
        "2022.3.1" to "17.0.6-b653.34",
        "2022.3.2" to "17.0.6-b653.34",
        "2022.3.3" to "17.0.6-b653.34",
        "2023.2" to "17.0.8.1-b1000.32",
        "2023.2.1" to "17.0.10-b1000.48",
        "2023.2.3" to "17.0.12-b1000.54",
        "2023.3" to "17.0.9-b1087.9",
    ).mapKeys { MpsVersion.parse(it.key) }.mapValues { JbrVersion.parse(it.value) }

    /** no JBR of the required build line is published on Nexus */
    internal val UNSUPPORTED = setOf(MpsVersion.parse("2025.2.1"))

    /**
     * @param markerVersion `jbr_jcef` version from the marker POM, null if there is none for [mpsVersion]
     * @param publishedVersions all `jbr_jcef` versions on Nexus; unparsable entries (e.g. JBR 11 notation) are ignored
     * @param isPublished whether the artifact for the target platform exists for a version
     */
    fun resolve(
        mpsVersion: MpsVersion,
        platform: Platform,
        explicitVersion: String?,
        markerVersion: String?,
        publishedVersions: List<String>,
        isPublished: (JbrVersion) -> Boolean,
    ): JbrResolution {
        if (mpsVersion < MpsVersion.FIRST_SUPPORTED) {
            throw JbrResolutionException("MPS $mpsVersion is not supported. Supported: ${MpsVersion.FIRST_SUPPORTED} and later.")
        }

        if (explicitVersion != null) {
            val explicit = JbrVersion.parse(explicitVersion)
            if (!isPublished(explicit)) {
                throw JbrResolutionException("JBR $explicit (${platform.jbrClassifier}) is not published on Nexus.")
            }
            return JbrResolution(explicit, JbrResolution.Source.EXPLICIT)
        }

        if (mpsVersion in UNSUPPORTED) {
            throw JbrResolutionException(
                "MPS $mpsVersion is not supported: no matching JBR is published on Nexus. Set 'jbrVersion' explicitly to use it anyway."
            )
        }

        val (wanted, source) = markerVersion?.let { JbrVersion.parse(it) to JbrResolution.Source.MARKER }
            ?: BUILT_IN[mpsVersion]?.let { it to JbrResolution.Source.BUILT_IN_TABLE }
            ?: throw JbrResolutionException("No JBR known for MPS $mpsVersion. Set 'jbrVersion' explicitly.")

        if (isPublished(wanted)) return JbrResolution(wanted, source)

        val fallback = publishedVersions.asSequence()
            .mapNotNull { JbrVersion.parseOrNull(it) }
            .filter { it.buildLine == wanted.buildLine }
            .sortedDescending()
            .firstOrNull(isPublished)
            ?: throw JbrResolutionException(
                "JBR $wanted (${platform.jbrClassifier}) required by MPS $mpsVersion is not published on Nexus, " +
                    "and no other build of line ${wanted.buildLine} is available for this platform. Set 'jbrVersion' explicitly."
            )

        return JbrResolution(
            fallback, JbrResolution.Source.BUILD_LINE_FALLBACK,
            warning = "JBR $wanted required by MPS $mpsVersion is not published on Nexus; using $fallback of the same build line instead."
        )
    }
}
