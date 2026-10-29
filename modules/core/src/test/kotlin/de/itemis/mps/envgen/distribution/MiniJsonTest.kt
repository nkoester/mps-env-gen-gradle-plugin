package de.itemis.mps.envgen.distribution

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class MiniJsonTest {

    @Test
    fun `parses a product-info like descriptor`() {
        val json = MiniJson.parse(
            """
            { "name": "MPS", "version": "2026.1", "launch": [
                { "os": "Linux", "arch": "amd64", "launcherPath": "bin/mps", "additionalJvmArguments": ["-Da=\"b\"", "-Dc=é"] }
              ], "bundledPlugins": [], "x": null, "n": -1.5e2, "t": true }
            """
        ) as Map<*, *>
        val launch = (json["launch"] as List<*>).single() as Map<*, *>
        assertEquals("Linux", launch["os"])
        assertEquals(listOf("-Da=\"b\"", "-Dc=é"), launch["additionalJvmArguments"])
        assertEquals(emptyList<Any>(), json["bundledPlugins"])
        assertEquals(null, json["x"])
        assertEquals(-150.0, json["n"])
        assertEquals(true, json["t"])
    }

    @Test
    fun `rejects malformed input`() {
        listOf("{", "{\"a\" 1}", "[1,]", "\"abc", "{} x", "nul").forEach {
            assertThrows<MiniJson.JsonException>(it) { MiniJson.parse(it) }
        }
    }
}
