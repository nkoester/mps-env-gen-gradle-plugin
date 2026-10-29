package de.itemis.mps.envgen.distribution

/**
 * Minimal JSON reader for descriptors like `product-info.json`; avoids a JSON library (and Groovy, which is not
 * available in the native CLI). Objects become [Map], arrays [List], numbers [Double].
 */
internal object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? = Reader(text).run {
        val value = readValue()
        skipWhitespace()
        if (pos != text.length) fail("trailing content")
        value
    }

    private class Reader(val text: String) {
        var pos = 0

        fun fail(message: String): Nothing = throw JsonException("Invalid JSON at $pos: $message")

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) fail("unexpected end")
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> readNumber()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return value
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (pos >= text.length || text[pos] != c) fail("expected '$c'")
            pos++
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (text.getOrNull(pos) == '}') return result.also { pos++ }
            while (true) {
                skipWhitespace()
                val key = readString()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> return result.also { pos++ }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (text.getOrNull(pos) == ']') return result.also { pos++ }
            while (true) {
                result += readValue()
                skipWhitespace()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> return result.also { pos++ }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            if (text.getOrNull(pos) != '"') fail("expected string")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("short unicode escape")
                                sb.append(text.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            return text.substring(start, pos).toDoubleOrNull() ?: fail("unexpected character '${text.getOrNull(start)}'")
        }
    }
}
