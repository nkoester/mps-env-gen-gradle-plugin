package de.itemis.mps.envgen.distribution

import java.io.File

/**
 * Access to the Nexus artifacts needed for a runnable MPS. Implemented by Gradle dependency resolution in the
 * plugin; a standalone client can implement it without Gradle.
 */
interface ArtifactSource {
    /** generic MPS zip `com.jetbrains:mps:<version>@zip` */
    fun mpsArchive(version: MpsVersion): File

    /** `jbr_jcef` version required by the marker POM `com.jetbrains.mps:mps-jbr:<version>`, null without marker */
    fun markerJbrVersion(version: MpsVersion): String?

    /** all published `jbr_jcef` versions */
    fun publishedJbrVersions(): List<String>

    /** `com.jetbrains.jdk:jbr_jcef:<version>:<classifier>@tgz`, null if not published */
    fun jbrArchive(version: JbrVersion, classifier: String): File?
}

data class ResolvedJbr(val resolution: JbrResolution, val archive: File)

/** resolves the JBR for [mpsVersion] on [platform] and fetches its archive; see [JbrResolver] for the rules */
fun ArtifactSource.resolveJbr(mpsVersion: MpsVersion, platform: Platform, explicitVersion: String?): ResolvedJbr {
    val archives = mutableMapOf<JbrVersion, File?>()
    val archive = { v: JbrVersion -> archives.getOrPut(v) { jbrArchive(v, platform.jbrClassifier) } }

    // marker and version list are only needed without an explicit version / if the wanted build is missing
    val marker = if (explicitVersion == null) markerJbrVersion(mpsVersion) else null
    val published by lazy { publishedJbrVersions() }
    val resolution = JbrResolver.resolve(
        mpsVersion, platform, explicitVersion, marker,
        publishedVersions = LazyList { published },
        isPublished = { archive(it) != null },
    )
    return ResolvedJbr(resolution, checkNotNull(archive(resolution.version)))
}

/** defers the (expensive) version listing until the fallback actually iterates it */
private class LazyList<T>(supplier: () -> List<T>) : AbstractList<T>() {
    private val delegate by lazy(supplier)
    override val size: Int get() = delegate.size
    override fun get(index: Int): T = delegate[index]
}
