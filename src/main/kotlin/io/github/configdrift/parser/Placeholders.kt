package io.github.configdrift.parser

/** A single `${name:default}` reference found inside a value. */
data class PlaceholderRef(
    val name: String,
    val defaultValue: String?,
    /** Offset of the `$` within the value string. */
    val startInValue: Int,
    val raw: String,
) {
    val hasDefault: Boolean get() = defaultValue != null
}

/**
 * Extracts `${...}` references from config values.
 *
 * Nesting is handled by brace counting rather than a regex, because `${a:${b}}` is legal and a
 * non-greedy regex truncates it at the first `}`.
 */
object Placeholders {

    fun parse(value: String): List<PlaceholderRef> {
        val refs = mutableListOf<PlaceholderRef>()
        var i = 0
        while (i < value.length - 1) {
            if (value[i] != '$' || value[i + 1] != '{') {
                i++
                continue
            }
            val end = matchingBrace(value, i + 1)
            if (end < 0) break

            val body = value.substring(i + 2, end)
            val separator = topLevelColon(body)
            refs += PlaceholderRef(
                name = if (separator < 0) body else body.substring(0, separator),
                defaultValue = if (separator < 0) null else body.substring(separator + 1),
                startInValue = i,
                raw = value.substring(i, end + 1),
            )
            i = end + 1
        }
        return refs
    }

    /** True when the value is nothing but one placeholder — the correctly externalized case. */
    fun isFullyExternalized(value: String): Boolean {
        val refs = parse(value)
        return refs.size == 1 && refs[0].raw == value.trim() && !refs[0].hasDefault
    }

    private fun matchingBrace(text: String, openBraceIndex: Int): Int {
        var depth = 0
        for (i in openBraceIndex until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** The `:` that separates name from default, ignoring any inside a nested placeholder. */
    private fun topLevelColon(body: String): Int {
        var depth = 0
        for (i in body.indices) {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
                ':' -> if (depth == 0) return i
            }
        }
        return -1
    }
}
