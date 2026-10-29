package de.itemis.mps.envgen.environment

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Properties

/** picks HTTP and debug ports so environments next to each other can run at the same time */
object Ports {

    const val HTTP_BASE = 63321
    const val DEBUG_BASE = 51337
    private const val RANGE = 1000

    data class Used(val http: Set<Int>, val debug: Set<Int>)

    /** ports recorded by the environments in [targetPath], except [exclude] (the one being regenerated) */
    fun usedBySiblings(targetPath: File, exclude: File? = null): Used {
        val http = mutableSetOf<Int>()
        val debug = mutableSetOf<Int>()
        val excluded = exclude?.absoluteFile?.normalize()
        targetPath.listFiles().orEmpty()
            .filter { it.isDirectory && it.absoluteFile.normalize() != excluded }
            .mapNotNull { EnvironmentMetadata.file(it).takeIf(File::isFile) }
            .forEach { file ->
                val props = Properties().apply { runCatching { file.reader().use { load(it) } } }
                props.getProperty("httpPort")?.toIntOrNull()?.let { http += it }
                props.getProperty("debug.port")?.toIntOrNull()?.let { debug += it }
            }
        return Used(http, debug)
    }

    /** first port from [base] neither in [taken] nor currently bound on this machine */
    fun allocate(base: Int, taken: Set<Int>, isFree: (Int) -> Boolean = ::isFree): Int =
        (base until base + RANGE).firstOrNull { it !in taken && isFree(it) }
            ?: throw IllegalStateException("No free port in $base..${base + RANGE - 1}")

    fun isFree(port: Int): Boolean = try {
        ServerSocket().use {
            it.reuseAddress = false
            it.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        }
        true
    } catch (e: IOException) {
        false
    }
}
