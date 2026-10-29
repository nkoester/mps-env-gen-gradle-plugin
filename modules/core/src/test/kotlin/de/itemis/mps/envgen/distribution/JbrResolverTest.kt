package de.itemis.mps.envgen.distribution

import de.itemis.mps.envgen.OS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JbrResolverTest {

    private val linux = Platform.of(OS.LINUX, Arch.AMD64)

    // excerpt of jbr_jcef versions on Nexus (2026-10-28)
    private val published = listOf(
        "11_0_10-b1145.96", "17.0.3-b469.32", "17.0.6-b469.82", "17.0.6-b653.34", "17.0.8.1-b1000.32",
        "17.0.10-b1000.50", "17.0.10-b1087.23", "17.0.11-b1207.23", "17.0.11-b1207.30", "17.0.12-b1000.54",
        "21.0.8-b1138.52", "21.0.8-b1163.69", "25.0.3-b329.124",
    )

    private fun resolve(
        mps: String,
        marker: String? = null,
        explicit: String? = null,
        isPublished: (JbrVersion) -> Boolean = { it.toString() in published },
    ) = JbrResolver.resolve(MpsVersion.parse(mps), linux, explicit, marker, published, isPublished)

    @Test
    fun `uses marker version when published`() {
        val r = resolve("2026.1", marker = "25.0.3-b329.124")
        assertEquals("25.0.3-b329.124", r.version.toString())
        assertEquals(JbrResolution.Source.MARKER, r.source)
        assertNull(r.warning)
    }

    @Test
    fun `uses built-in table without marker`() {
        val r = resolve("2022.2.4")
        assertEquals("17.0.6-b469.82", r.version.toString())
        assertEquals(JbrResolution.Source.BUILT_IN_TABLE, r.source)
    }

    @Test
    fun `falls back to newest published build of the same line`() {
        // 2023.2.1 bundles 17.0.10-b1000.48, which is not on Nexus
        val r = resolve("2023.2.1")
        assertEquals("17.0.12-b1000.54", r.version.toString())
        assertEquals(JbrResolution.Source.BUILD_LINE_FALLBACK, r.source)
        assertNotNull(r.warning)
    }

    @Test
    fun `fallback skips builds missing for the platform`() {
        val r = resolve("2023.2.1", isPublished = { it.toString() in published && it.toString() != "17.0.12-b1000.54" })
        assertEquals("17.0.10-b1000.50", r.version.toString())
    }

    @Test
    fun `fails without any build of the line`() {
        // marker of 2024.1 points to an unpublished build line in this excerpt
        val e = assertThrows<JbrResolutionException> { resolve("2024.1", marker = "17.0.11-b9999.1") }
        assertTrue("b9999" in e.message!!)
    }

    @Test
    fun `2025_2_1 is unsupported unless set explicitly`() {
        assertThrows<JbrResolutionException> { resolve("2025.2.1", marker = "21.0.8-b1038.71") }
        val r = resolve("2025.2.1", explicit = "21.0.8b1163.69")
        assertEquals("21.0.8-b1163.69", r.version.toString())
        assertEquals(JbrResolution.Source.EXPLICIT, r.source)
    }

    @Test
    fun `explicit version must be published`() {
        assertThrows<JbrResolutionException> { resolve("2026.1", explicit = "25.0.3-b1.1") }
    }

    @Test
    fun `rejects MPS before 2022_2`() {
        assertThrows<JbrResolutionException> { resolve("2021.3.5", explicit = "17.0.6-b469.82") }
    }

    @Test
    fun `fails for unknown versions without marker`() {
        assertThrows<JbrResolutionException> { resolve("2023.3-RC1") }
    }

    @Test
    fun `built-in table only covers versions from 2022_2`() {
        assertTrue(JbrResolver.BUILT_IN.keys.all { it >= MpsVersion.FIRST_SUPPORTED })
    }
}
