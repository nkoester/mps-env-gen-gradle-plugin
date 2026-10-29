package de.itemis.mps.envgen.distribution

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionsTest {

    @ParameterizedTest
    @CsvSource("2022.2,2022.2", "2024.1.5,2024.1.5", "2026.1-RC1,2026.1-RC1", "2024.3-EAP1,2024.3-EAP1", "2025.1.0,2025.1")
    fun `parses and prints MPS versions`(input: String, expected: String) {
        assertEquals(expected, MpsVersion.parse(input).toString())
    }

    @Test
    fun `orders MPS versions with pre-releases before their release`() {
        val sorted = listOf("2024.1.1", "2024.1", "2024.1-RC1", "2023.3.2", "2024.1-EAP1").map(MpsVersion::parse).sorted()
        assertEquals(listOf("2023.3.2", "2024.1-EAP1", "2024.1-RC1", "2024.1", "2024.1.1"), sorted.map { it.toString() })
        assertTrue(MpsVersion.parse("2022.1") < MpsVersion.FIRST_SUPPORTED)
    }

    @Test
    fun `rejects invalid MPS versions`() {
        assertThrows<IllegalArgumentException> { MpsVersion.parse("24.1") }
        assertThrows<IllegalArgumentException> { MpsVersion.parse("latest") }
    }

    @ParameterizedTest
    @CsvSource("21.0.8b1038.71", "21.0.8-b1038.71", "21.0.8+9-b1038.71")
    fun `normalizes JBR notations`(input: String) {
        val v = JbrVersion.parse(input)
        assertEquals("21.0.8-b1038.71", v.toString())
        assertEquals("b1038", v.buildLine)
    }

    @Test
    fun `orders JBR versions by build`() {
        val sorted = listOf("17.0.12-b1000.54", "17.0.10-b1000.50", "17.0.8.1-b1000.32").map(JbrVersion::parse).sorted()
        assertEquals(listOf("17.0.8.1-b1000.32", "17.0.10-b1000.50", "17.0.12-b1000.54"), sorted.map { it.toString() })
    }

    @Test
    fun `ignores JBR 11 notation`() {
        assertEquals(null, JbrVersion.parseOrNull("11_0_10-b1145.96"))
    }
}
