package de.itemis.mps.envgen.cli

import de.itemis.mps.envgen.distribution.ArtifactSource
import de.itemis.mps.envgen.distribution.JbrVersion
import de.itemis.mps.envgen.distribution.MpsVersion
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * [ArtifactSource] talking to a Maven repository (the itemis Nexus) over HTTP. Downloads are kept in [cacheDir]
 * using the Maven layout, so the cache can be inspected and cleaned by hand.
 */
class NexusArtifactSource(
    private val repositoryUrl: String,
    private val cacheDir: File,
    private val log: (String) -> Unit = {},
    private val progress: (String) -> Unit = {},
) : ArtifactSource {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val baseUrl = repositoryUrl.trimEnd('/')

    override fun mpsArchive(version: MpsVersion): File =
        fetch("com/jetbrains/mps/$version/mps-$version.zip")
            ?: throw IOException("MPS $version is not published at $repositoryUrl (com.jetbrains:mps:$version@zip)")

    override fun markerJbrVersion(version: MpsVersion): String? {
        val pom = getText("com/jetbrains/mps/mps-jbr/$version/mps-jbr-$version.pom") ?: return null
        return MARKER_JBR.find(pom)?.groupValues?.get(1)?.trim()
    }

    override fun publishedJbrVersions(): List<String> {
        val metadata = getText("com/jetbrains/jdk/jbr_jcef/maven-metadata.xml") ?: return emptyList()
        return VERSION.findAll(metadata).map { it.groupValues[1].trim() }.toList()
    }

    override fun jbrArchive(version: JbrVersion, classifier: String): File? =
        fetch("com/jetbrains/jdk/jbr_jcef/$version/jbr_jcef-$version-$classifier.tgz")

    /** cached file for [path], downloaded if missing; null if the repository does not have it */
    private fun fetch(path: String): File? {
        val cached = File(cacheDir, path)
        if (cached.isFile) {
            log("Using cached $cached")
            return cached
        }
        cached.parentFile.mkdirs()
        val partial = File(cached.path + ".part")
        val response = client.send(request(path), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() == 404) {
            response.body().close()
            return null
        }
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("Download of ${url(path)} failed: HTTP ${response.statusCode()}")
        }

        log("Downloading ${url(path)}")
        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1)
        val digest = MessageDigest.getInstance("SHA-1")
        response.body().use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var done = 0L
                var lastPercent = -1L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    done += read
                    val percent = if (total > 0) done * 100 / total else -1
                    if (percent >= 0 && percent / 10 != lastPercent / 10) {
                        progress("  ${cached.name}: $percent% (${done / MB} of ${total / MB} MB)")
                        lastPercent = percent
                    }
                }
            }
        }

        verifySha1(path, digest.digest().joinToString("") { "%02x".format(it) }, partial)
        Files.move(partial.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return cached
    }

    private fun verifySha1(path: String, actual: String, file: File) {
        val expected = getText("$path.sha1")?.trim()?.substringBefore(' ')
        if (expected == null) {
            log("Warning: no checksum published for ${url(path)}")
            return
        }
        if (!expected.equals(actual, ignoreCase = true)) {
            file.delete()
            throw IOException("Checksum mismatch for ${url(path)}: expected $expected, got $actual")
        }
    }

    private fun getText(path: String): String? {
        val response = client.send(request(path), HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> response.body()
            404 -> null
            else -> throw IOException("Request to ${url(path)} failed: HTTP ${response.statusCode()}")
        }
    }

    private fun request(path: String): HttpRequest = HttpRequest.newBuilder(URI.create(url(path))).GET().build()

    private fun url(path: String) = "$baseUrl/$path"

    companion object {
        const val DEFAULT_URL = "https://artifacts.itemis.cloud/repository/maven-mps"
        private const val MB = 1024 * 1024
        private val MARKER_JBR = Regex("""(?s)<artifactId>\s*jbr_jcef\s*</artifactId>\s*<version>([^<]+)</version>""")
        private val VERSION = Regex("""<version>([^<]+)</version>""")
    }
}
