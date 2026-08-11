package io.github.configdrift.metadata

/**
 * A minimal JSON reader, sufficient for `spring-configuration-metadata.json`.
 *
 * Hand-rolled on purpose: the alternatives were bundling a JSON library (duplicating one the
 * platform already ships) or compiling against a platform-internal one (unstable across
 * releases). Metadata files are machine-generated and small, so a 90-line reader with no
 * dependency and no version coupling is the cheaper trade.
 *
 * Returns plain Kotlin types: [Map]`<String, Any?>`, [List]`<Any?>`, [String], [Double],
 * [Boolean], or null.
 */
object MiniJson {

    class ParseException(message: String, val offset: Int) :
        RuntimeException("$message at offset $offset")

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd) throw ParseException("Trailing content", reader.position)
        return value
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        val atEnd: Boolean get() = position >= text.length

        fun skipWhitespace() {
            while (!atEnd && text[position].isWhitespace()) position++
        }

        fun readValue(): Any? {
            if (atEnd) throw ParseException("Unexpected end of input", position)
            return when (val ch = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else ->
                    if (ch == '-' || ch.isDigit()) readNumber()
                    else throw ParseException("Unexpected character '$ch'", position)
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { position++; return result }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> { position++; return result }
                    else -> throw ParseException("Expected ',' or '}'", position)
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { position++; return result }
            while (true) {
                skipWhitespace()
                result += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> { position++; return result }
                    else -> throw ParseException("Expected ',' or ']'", position)
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd) throw ParseException("Unterminated string", position)
                when (val ch = text[position++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscape())
                    else -> builder.append(ch)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd) throw ParseException("Unterminated escape", position)
            return when (val ch = text[position++]) {
                '"', '\\', '/' -> ch
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val hex = text.substring(position, (position + 4).coerceAtMost(text.length))
                    position += hex.length
                    hex.toIntOrNull(16)?.toChar()
                        ?: throw ParseException("Bad unicode escape '$hex'", position)
                }
                else -> throw ParseException("Bad escape '\\$ch'", position)
            }
        }

        private fun readNumber(): Double {
            val start = position
            if (peek() == '-') position++
            while (!atEnd && (text[position].isDigit() || text[position] in ".eE+-")) position++
            val literal = text.substring(start, position)
            return literal.toDoubleOrNull()
                ?: throw ParseException("Bad number '$literal'", start)
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, position)) {
                throw ParseException("Expected '$literal'", position)
            }
            position += literal.length
            return value
        }

        private fun peek(): Char =
            if (atEnd) throw ParseException("Unexpected end of input", position) else text[position]

        private fun expect(expected: Char) {
            if (peek() != expected) throw ParseException("Expected '$expected'", position)
            position++
        }
    }
}
