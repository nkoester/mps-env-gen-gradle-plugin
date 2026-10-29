package de.itemis.mps.envgen.distribution

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** JDK-only extraction for the MPS zip and the JBR tar.gz, shared by the Gradle plugin and the CLI */
object Archives {

    fun unzip(archive: File, into: File) {
        val root = into.toPath().toAbsolutePath().normalize()
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val target = resolveInside(root, entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    continue
                }
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    /** extracts a gzip compressed tar (ustar, GNU long names, PAX paths) keeping file modes and symlinks */
    fun untarGz(archive: File, into: File) {
        val root = into.toPath().toAbsolutePath().normalize()
        val deferredLinks = mutableListOf<Pair<Path, Path>>()
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            val input = DataInputStream(gz)
            var longName: String? = null
            var longLink: String? = null
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readBlock(input, header)) break
                if (header.all { it == 0.toByte() }) break

                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val name = longName ?: entryName(header)
                val link = longLink ?: string(header, 157, 100)
                longName = null
                longLink = null

                when (type) {
                    'L' -> { longName = readData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000'); continue }
                    'K' -> { longLink = readData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000'); continue }
                    'x' -> {
                        val pax = parsePax(readData(input, size))
                        longName = pax["path"]
                        longLink = pax["linkpath"]
                        continue
                    }
                    'g' -> { skip(input, size); continue }
                }

                val target = resolveInside(root, name)
                when (type) {
                    '5' -> Files.createDirectories(target)
                    '2' -> {
                        Files.createDirectories(target.parent)
                        Files.deleteIfExists(target)
                        try {
                            Files.createSymbolicLink(target, Path.of(link))
                        } catch (e: FileSystemException) {
                            // e.g. Windows without symlink privilege: copy the target once everything is extracted
                            deferredLinks += target to target.parent.resolve(link).normalize()
                        } catch (e: UnsupportedOperationException) {
                            deferredLinks += target to target.parent.resolve(link).normalize()
                        }
                    }
                    '1' -> deferredLinks += target to resolveInside(root, link)
                    '0', '\u0000', '7' -> {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target).use { copy(input, it, size) }
                        skip(input, padding(size))
                        applyMode(target, octal(header, 100, 8).toInt())
                    }
                    else -> skip(input, size + padding(size))
                }
                if (type == '5' || type == '2' || type == '1') skip(input, size + padding(size))
            }
        }
        deferredLinks.forEach { (link, target) ->
            if (Files.isRegularFile(target)) {
                Files.createDirectories(link.parent)
                Files.copy(target, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    private const val BLOCK = 512

    /** rejects absolute paths and `..` segments that would leave the target directory */
    private fun resolveInside(root: Path, name: String): Path {
        val resolved = root.resolve(name.trimStart('/')).normalize()
        if (!resolved.startsWith(root)) throw IOException("Archive entry '$name' points outside of $root")
        return resolved
    }

    private fun entryName(header: ByteArray): String {
        val name = string(header, 0, 100)
        val isUstar = string(header, 257, 6).startsWith("ustar")
        val prefix = if (isUstar) string(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun string(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        // GNU base-256 encoding for large values
        if (header[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in offset + 1 until offset + length) value = (value shl 8) or (header[i].toLong() and 0xff)
            return value
        }
        val text = string(header, offset, length).trim()
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    /** PAX records: `<length> <key>=<value>\n` */
    private fun parsePax(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var pos = 0
        while (pos < data.size) {
            val space = (pos until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val length = String(data, pos, space - pos, Charsets.UTF_8).toIntOrNull() ?: break
            if (length <= 0 || pos + length > data.size) break
            val record = String(data, space + 1, pos + length - space - 2, Charsets.UTF_8)
            record.indexOf('=').takeIf { it > 0 }?.let { result[record.substring(0, it)] = record.substring(it + 1) }
            pos += length
        }
        return result
    }

    private fun applyMode(file: Path, mode: Int) {
        val permissions = buildSet {
            if (mode and 0b100_000_000 != 0) add(PosixFilePermission.OWNER_READ)
            if (mode and 0b010_000_000 != 0) add(PosixFilePermission.OWNER_WRITE)
            if (mode and 0b001_000_000 != 0) add(PosixFilePermission.OWNER_EXECUTE)
            if (mode and 0b000_100_000 != 0) add(PosixFilePermission.GROUP_READ)
            if (mode and 0b000_010_000 != 0) add(PosixFilePermission.GROUP_WRITE)
            if (mode and 0b000_001_000 != 0) add(PosixFilePermission.GROUP_EXECUTE)
            if (mode and 0b000_000_100 != 0) add(PosixFilePermission.OTHERS_READ)
            if (mode and 0b000_000_010 != 0) add(PosixFilePermission.OTHERS_WRITE)
            if (mode and 0b000_000_001 != 0) add(PosixFilePermission.OTHERS_EXECUTE)
        }
        try {
            Files.setPosixFilePermissions(file, permissions + PosixFilePermission.OWNER_READ + PosixFilePermission.OWNER_WRITE)
        } catch (e: UnsupportedOperationException) {
            if (mode and 0b001_000_000 != 0) file.toFile().setExecutable(true, false)
        }
    }

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun readBlock(input: DataInputStream, block: ByteArray): Boolean = try {
        input.readFully(block)
        true
    } catch (e: EOFException) {
        false
    }

    private fun readData(input: DataInputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        input.readFully(data)
        skip(input, padding(size))
        return data
    }

    private fun copy(input: InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Truncated tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) throw EOFException("Truncated tar archive")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
