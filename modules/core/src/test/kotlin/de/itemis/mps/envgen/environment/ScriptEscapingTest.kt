package de.itemis.mps.envgen.environment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScriptEscapingTest {
    @Test
    fun `escapes shell double quote metacharacters`() {
        assertEquals("a \\\$HOME \\`x\\` \\\"q\\\" \\\\", escapeShell("a \$HOME `x` \"q\" \\"))
    }

    @Test
    fun `escapes batch values by context`() {
        assertEquals("C:\\a%%b (x86) & c", escapeBatchQuoted("C:\\a%b (x86) & c"))
        assertEquals("run ^& ^(go^) 100%%", escapeBatchEcho("run & (go) 100%"))
    }
}
