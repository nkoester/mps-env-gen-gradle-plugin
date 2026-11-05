package de.itemis.mps.envgen.plugins

import de.itemis.mps.envgen.environment.MpsBuild

/** whether a plugin fits an MPS build, judged from its `idea-version` range */
sealed class Compatibility {
    object Compatible : Compatibility()

    /** outside the declared range: MPS would disable the plugin */
    data class Incompatible(val reason: String) : Compatibility()

    /** not decidable from the descriptor; the user decides */
    data class Uncertain(val reason: String) : Compatibility()

    companion object {
        /**
         * IntelliJ's rule (`since-build` <= build <= `until-build`) plus one of ours: without `until-build`, a plugin
         * built for another branch is uncertain. Plugins shipped per MPS series only declare
         * `since-build`, so IntelliJ would load the 2025.1 build into MPS 2026.1.
         */
        fun of(descriptor: PluginDescriptor?, build: MpsBuild?): Compatibility {
            if (descriptor == null) return Uncertain("it has no readable META-INF/plugin.xml")
            if (build == null) return Uncertain("the MPS build is unknown (no build.txt)")
            val since = descriptor.sinceBuild ?: return Uncertain("it declares no since-build")
            val mps = parse(build.number)
            val mpsName = "MPS ${build.series} (${build.number})"

            if (compare(parse(since), mps, wildcard = 0) > 0) {
                return Incompatible("it requires build $since or later (${seriesOf(since)}), the environment uses $mpsName")
            }
            descriptor.untilBuild?.let { until ->
                return if (compare(parse(until), mps, wildcard = Int.MAX_VALUE) < 0) {
                    Incompatible("it supports builds up to $until, the environment uses $mpsName")
                } else {
                    Compatible
                }
            }
            val sinceBranch = parse(since).first()
            return if (sinceBranch != build.baseline) {
                Uncertain("it is built for $since (${seriesOf(since)}), the environment uses $mpsName")
            } else {
                Compatible
            }
        }

        /** components of a build number; `*` and missing components are filled with [wildcard] when comparing */
        private fun parse(build: String): List<Int?> =
            build.substringAfter('-').split('.').map { if (it == "*") null else it.toIntOrNull() ?: 0 }

        private fun compare(a: List<Int?>, b: List<Int?>, wildcard: Int): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i) ?: wildcard
                val y = b.getOrNull(i) ?: 0
                if (x != y) return x.compareTo(y)
            }
            return 0
        }

        private fun seriesOf(build: String): String =
            parse(build).first()?.let { MpsBuild.seriesOf(it) } ?: build
    }
}
