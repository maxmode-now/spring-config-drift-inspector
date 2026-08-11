package io.github.configdrift.parser

/** One `KEY=value` line from a `.env` file. */
data class DotenvLine(val key: String, val value: String)

/**
 * Parses a single `.env` line. Deliberately minimal, matching this project's established style of
 * listing known limitations rather than reimplementing a library (see, e.g., [ProfileResolver]'s
 * `on-profile` boolean-expression handling):
 *  - No escape-sequence decoding inside quoted values (`\n`, `\t`, etc. are kept literal).
 *  - No multi-line quoted values — each line is parsed independently.
 *  - No trailing same-line comment stripping (`KEY=value # comment`): real dotenv implementations
 *    disagree on this, and guessing wrong would silently corrupt a value that legitimately
 *    contains `#`.
 */
object DotenvParsing {

    fun parseLine(rawLine: String): DotenvLine? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return null

        val withoutExport = line.removePrefix("export ").trimStart()
        val separatorIndex = withoutExport.indexOf('=')
        if (separatorIndex < 0) return null

        val key = withoutExport.substring(0, separatorIndex).trim()
        if (key.isEmpty()) return null

        val rawValue = withoutExport.substring(separatorIndex + 1).trim()
        return DotenvLine(key, unquote(rawValue))
    }

    /**
     * `internal`, not `private`: [DockerComposeConfigParser] reuses this for its `KEY=VALUE` list
     * form (`- DB_HOST="localhost"`), the same env-line value syntax dotenv uses — see that class
     * for why reimplementing it separately there caused drift false positives.
     */
    internal fun unquote(value: String): String {
        if (value.length < 2) return value
        val quote = value.first()
        if ((quote == '"' || quote == '\'') && value.last() == quote) {
            return value.substring(1, value.length - 1)
        }
        return value
    }
}
