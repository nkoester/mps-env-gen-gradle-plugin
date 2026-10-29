package de.itemis.mps.envgen.gradle

import de.itemis.mps.envgen.distribution.ArtifactSource
import de.itemis.mps.envgen.distribution.JbrVersion
import de.itemis.mps.envgen.distribution.MpsVersion
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import java.io.File

/**
 * [ArtifactSource] backed by Gradle dependency resolution against the project repositories,
 * so the Gradle cache, offline mode, mirrors and credentials apply.
 */
internal class GradleArtifactSource(private val project: Project) : ArtifactSource {

    override fun mpsArchive(version: MpsVersion): File {
        val notation = "$MPS_GROUP:$MPS_ARTIFACT:$version@zip"
        return detached(notation).incoming.artifactView { lenient(true) }.files.singleOrNull()
            ?: throw GradleException(
                "Unable to resolve $notation. Make sure a repository providing MPS is configured, e.g. " +
                    "repositories { maven(\"$NEXUS_URL\") }"
            )
    }

    override fun markerJbrVersion(version: MpsVersion): String? {
        val root = detached("$MARKER_GROUP:$MARKER_ARTIFACT:$version").incoming.resolutionResult.root
        return when (val marker = root.dependencies.singleOrNull()) {
            is ResolvedDependencyResult -> marker.selected.dependencies
                .map { it.requested }
                .filterIsInstance<ModuleComponentSelector>()
                .firstOrNull { it.group == JBR_GROUP && it.module == JBR_ARTIFACT }
                ?.version

            // no marker for this MPS version; other failures (network, auth) must not be mistaken for that
            is UnresolvedDependencyResult -> if (marker.failure.isNotFound()) null else throw marker.failure
            else -> null
        }
    }

    override fun publishedJbrVersions(): List<String> {
        // Gradle has no API to list versions; a rejecting selection rule sees every candidate of the dynamic version
        val versions = mutableListOf<String>()
        val configuration = detached("$JBR_GROUP:$JBR_ARTIFACT:+").apply {
            resolutionStrategy.componentSelection.all {
                versions += candidate.version
                reject("listing versions only")
            }
        }
        configuration.incoming.resolutionResult.root.dependencies.size
        return versions
    }

    override fun jbrArchive(version: JbrVersion, classifier: String): File? =
        detached("$JBR_GROUP:$JBR_ARTIFACT:$version:$classifier@tgz").incoming.artifactView { lenient(true) }.files.singleOrNull()

    private fun detached(notation: String): Configuration =
        project.configurations.detachedConfiguration(project.dependencies.create(notation))

    private fun Throwable.isNotFound(): Boolean =
        generateSequence(this) { it.cause }.any { it.javaClass.simpleName == "ModuleVersionNotFoundException" }

    companion object {
        const val NEXUS_URL = "https://artifacts.itemis.cloud/repository/maven-mps"
        const val MPS_GROUP = "com.jetbrains"
        const val MPS_ARTIFACT = "mps"
        const val MARKER_GROUP = "com.jetbrains.mps"
        const val MARKER_ARTIFACT = "mps-jbr"
        const val JBR_GROUP = "com.jetbrains.jdk"
        const val JBR_ARTIFACT = "jbr_jcef"
    }
}
