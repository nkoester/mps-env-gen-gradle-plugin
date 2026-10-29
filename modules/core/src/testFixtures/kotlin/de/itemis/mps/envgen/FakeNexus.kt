package de.itemis.mps.envgen

import de.itemis.mps.envgen.distribution.Platform
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** local Maven repository with the layout of the itemis Nexus (`maven-mps`) */
class FakeNexus(val root: File) {

    val url: String get() = root.toURI().toString()

    /** generic MPS zip like on Nexus: no top-level folder, no executable bits, Linux-only launch record */
    fun mps(version: String) {
        val dir = artifactDir("com.jetbrains", "mps", version)
        File(dir, "mps-$version.pom").writeText(pom("com.jetbrains", "mps", version, packaging = "zip"))
        val files = listOf(
            "bin/mps.sh", "bin/mps64.vmoptions",
            "bin/linux/mps", "bin/linux/fsnotifier", "bin/linux/restarter",
            "bin/win/mps.bat", "bin/win/mps64.exe",
            "bin/mac/fsnotifier", "bin/mac/aarch64/restarter", "bin/mac/amd64/restarter",
            "bin/mac/Contents/MacOS/mps-x64", "bin/mac/Contents/MacOS/mps-aarch64", "bin/mac/Contents/Info.plist",
            "lib/jna/amd64/libjnidispatch.so", "lib/jna/aarch64/libjnidispatch.so", "lib/app.jar",
        ).associateWith { it } + mapOf(
            "product-info.json" to """{"launch":[{"os":"Linux","arch":"amd64","launcherPath":"bin/mps"}]}""",
            "build.txt" to "MPS-${buildNumber(version)}",
        )
        val zipFile = File(dir, "mps-$version.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        writeSha1(zipFile)
    }

    /** platform build like in real `build.txt` files: 2026.1 -> 261.1.1, 2022.2.4 -> 222.4.1 */
    private fun buildNumber(version: String): String {
        val (year, minor) = version.split('.')
        return "${year.takeLast(2)}${minor.substringBefore('-')}.${version.split('.').getOrElse(2) { "1" }}.1"
    }

    fun marker(mpsVersion: String, jbrVersion: String) {
        val dir = artifactDir("com.jetbrains.mps", "mps-jbr", mpsVersion)
        val dependency = "<dependencies><dependency><groupId>com.jetbrains.jdk</groupId><artifactId>jbr_jcef</artifactId>" +
            "<version>$jbrVersion</version></dependency></dependencies>"
        File(dir, "mps-jbr-$mpsVersion.pom").writeText(pom("com.jetbrains.mps", "mps-jbr", mpsVersion, "pom", dependency))
    }

    /** JBR tgz like on Nexus: single top folder, `Contents/Home` on macOS, executable `bin/java` */
    fun jbr(version: String, platform: Platform) {
        val dir = artifactDir("com.jetbrains.jdk", "jbr_jcef", version)
        File(dir, "jbr_jcef-$version.pom").writeText(pom("com.jetbrains.jdk", "jbr_jcef", version, "pom"))
        val top = "jbr_jcef-${version.substringBefore("-b")}-${platform.jbrClassifier}-b${version.substringAfter("-b")}"
        val javaHome = if (platform.javaHome == ".") top else "$top/${platform.javaHome}"
        val tgzFile = File(dir, "jbr_jcef-$version-${platform.jbrClassifier}.tgz")
        tgzFile.writeBytes(
            tgz(
                listOf(
                    TarEntry("$javaHome/${platform.javaExecutable}", "java $version", 0b111_101_101),
                    TarEntry("$javaHome/release", "JAVA_RUNTIME_VERSION=\"$version\"", 0b110_100_100),
                )
            )
        )
        writeSha1(tgzFile)
    }

    /** Nexus serves `<artifact>.sha1` next to every artifact */
    fun writeSha1(file: File) {
        val digest = MessageDigest.getInstance("SHA-1").digest(file.readBytes())
        File(file.path + ".sha1").writeText(digest.joinToString("") { "%02x".format(it) })
    }

    fun jbrVersionListing(vararg versions: String) {
        val dir = File(root, "com/jetbrains/jdk/jbr_jcef").apply { mkdirs() }
        File(dir, "maven-metadata.xml").writeText(
            "<metadata><groupId>com.jetbrains.jdk</groupId><artifactId>jbr_jcef</artifactId><versioning>" +
                "<latest>${versions.last()}</latest><release>${versions.last()}</release><versions>" +
                versions.joinToString("") { "<version>$it</version>" } + "</versions></versioning></metadata>"
        )
    }

    private fun artifactDir(group: String, artifact: String, version: String) =
        File(root, "${group.replace('.', '/')}/$artifact/$version").apply { mkdirs() }

    private fun pom(group: String, artifact: String, version: String, packaging: String, extra: String = "") =
        """<?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
        <groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version>
        <packaging>$packaging</packaging>$extra</project>""".trimIndent()

    data class TarEntry(
        val name: String,
        val content: String = "",
        val mode: Int = 0b110_100_100,
        /** '0' file, '5' directory, '2' symlink */
        val type: Char = '0',
        val linkName: String = "",
        /** long names as PAX header instead of GNU long name entry */
        val pax: Boolean = false,
    )

    companion object {
        /** minimal tar writer (ustar, GNU long names, PAX paths); the JDK has none */
        fun tgz(entries: List<TarEntry>): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { gz ->
                fun write(name: String, data: ByteArray, mode: Int, type: Char, linkName: String = "") {
                    gz.write(header(name, data.size.toLong(), mode, type, linkName))
                    gz.write(data)
                    gz.write(ByteArray((512 - data.size % 512) % 512))
                }
                entries.forEach { entry ->
                    var name = entry.name
                    if (name.length > 100) {
                        if (entry.pax) {
                            val record = "path=$name\n"
                            // the length field counts its own digits
                            var length = record.length + 2
                            while ("$length $record".length != length) length = "$length $record".length
                            write("PaxHeader", "$length $record".toByteArray(), 0b110_100_100, 'x')
                        } else {
                            write("././@LongLink", (name + "\u0000").toByteArray(), 0b110_100_100, 'L')
                        }
                        name = name.takeLast(100)
                    }
                    write(name, entry.content.toByteArray(), entry.mode, entry.type, entry.linkName)
                }
                gz.write(ByteArray(1024))
            }
            return out.toByteArray()
        }

        private fun header(name: String, size: Long, mode: Int, type: Char, linkName: String): ByteArray {
            val h = ByteArray(512)
            fun put(offset: Int, value: String) = value.toByteArray().copyInto(h, offset)
            fun octal(offset: Int, length: Int, value: Long) = put(offset, value.toString(8).padStart(length - 1, '0'))
            put(0, name)
            octal(100, 8, mode.toLong())
            octal(108, 8, 0)
            octal(116, 8, 0)
            octal(124, 12, size)
            octal(136, 12, 0)
            put(148, "        ")
            h[156] = type.code.toByte()
            put(157, linkName)
            put(257, "ustar\u000000")
            val checksum = h.sumOf { it.toInt() and 0xff }
            put(148, checksum.toString(8).padStart(6, '0') + "\u0000 ")
            return h
        }
    }
}
