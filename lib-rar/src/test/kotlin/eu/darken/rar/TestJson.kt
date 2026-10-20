package eu.darken.rar

/** Minimal JSON reader for the fixture manifests: objects, arrays, strings, integers, booleans, null. */
internal object TestJson {
    fun parse(text: String): Any? = Parser(text).run {
        val value = value()
        skipWhitespace()
        require(pos == text.length) { "Trailing data at $pos" }
        value
    }

    private class Parser(val text: String) {
        var pos = 0

        fun value(): Any? {
            skipWhitespace()
            return when (val c = text[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at $pos")
            }
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWhitespace()
            if (text[pos] == '}') return out.also { pos++ }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                out[key] = value()
                skipWhitespace()
                if (text[pos] == ',') pos++ else return out.also { expect('}') }
            }
        }

        fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipWhitespace()
            if (text[pos] == ']') return out.also { pos++ }
            while (true) {
                out += value()
                skipWhitespace()
                if (text[pos] == ',') pos++ else return out.also { expect(']') }
            }
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = text[pos++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Long {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            require(pos >= text.length || text[pos] !in ".eE") { "Only integers are supported at $start" }
            return text.substring(start, pos).toLong()
        }

        fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, pos)) { "Expected $word at $pos" }
            pos += word.length
            return value
        }

        fun expect(c: Char) {
            require(text[pos] == c) { "Expected '$c' at $pos, found '${text[pos]}'" }
            pos++
        }

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
