package io.github.configdrift.parser

/** A single `${name:default}` or `${name:-default}` reference found inside a value. */
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
 *
 * Two default-value syntaxes are recognized: Spring's `${name:default}` and bash/Compose's
 * `${name:-default}`. Bash also allows a colon-less `${name-default}`, but that form is not
 * supported here: a literal `-` can legitimately appear inside a Spring property name
 * (`${server.error.include-stacktrace}`, no default at all), and this parser is shared by every
 * format — recognizing bare `-` would misparse that as name=`server.error.include`,
 * default=`stacktrace`. A colon never appears in either convention's property names, so `:-` is
 * unambiguous where bare `-` is not; `${name-default}` is a documented gap, the same kind this
 * codebase already accepts for Spring's `on-profile` boolean expressions.
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
            val separator = topLevelSeparator(body)
            refs += PlaceholderRef(
                name = if (separator == null) body else body.substring(0, separator.index),
                defaultValue = if (separator == null) null else body.substring(separator.index + separator.length),
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

    private data class Separator(val index: Int, val length: Int)

    /**
     * The name/default separator — `:-` (bash/Compose) or `:` (Spring) — ignoring anything inside
     * a nested placeholder. `:-` is checked before treating a lone `:` as the separator, so
     * `${VAR:-default}` isn't parsed as `:` followed by a default that starts with `-`.
     */
    private fun topLevelSeparator(body: String): Separator? {
        var depth = 0
        for (i in body.indices) {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
                ':' -> if (depth == 0) {
                    val length = if (body.getOrNull(i + 1) == '-') 2 else 1
                    return Separator(i, length)
                }
            }
        }
        return null
    }
}
