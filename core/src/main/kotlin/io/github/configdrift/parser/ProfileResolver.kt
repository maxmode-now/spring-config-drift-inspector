package io.github.configdrift.parser

import io.github.configdrift.model.ProfileId

/**
 * Works out which profile a file or a YAML document belongs to.
 *
 * Two mechanisms have to be supported, and missing the second is the usual reason naive config
 * diff tools under-report:
 *  1. filename suffix — `application-prod.yml`
 *  2. `spring.config.activate.on-profile` inside a `---` separated document, which can put
 *     several profiles in one file
 */
object ProfileResolver {

    private val FILENAME = Regex("""^application(?:-(?<profile>[^.]+))?\.(ya?ml|properties)$""")

    /** Null when the filename is not an `application*` config file at all. */
    fun fromFileName(fileName: String): ProfileId? {
        val match = FILENAME.matchEntire(fileName) ?: return null
        val profile = match.groups["profile"]?.value
        return if (profile.isNullOrEmpty()) ProfileId.DEFAULT else ProfileId(profile)
    }

    fun isConfigFileName(fileName: String): Boolean = FILENAME.matches(fileName)

    /**
     * Parses an `on-profile` expression into the profiles it activates for.
     *
     * Spring allows boolean expressions (`prod & !legacy`). Rather than evaluating them — which
     * would be the start of reimplementing Spring — anything beyond a plain `a | b` / `a,b` list
     * is kept verbatim as a single pseudo-profile. It still appears as its own column, so the
     * user sees it and can judge it; it just is not silently mis-attributed to `prod`.
     */
    fun fromOnProfileExpression(expression: String): List<ProfileId> {
        val trimmed = expression.trim().trim('"', '\'')
        if (trimmed.isEmpty()) return listOf(ProfileId.DEFAULT)

        val isSimpleList = trimmed.none { it == '&' || it == '!' || it == '(' || it == ')' }
        if (!isSimpleList) return listOf(ProfileId(trimmed))

        return trimmed.split('|', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { ProfileId(it) }
            .ifEmpty { listOf(ProfileId.DEFAULT) }
    }

    /** The key that marks a document's profile in Spring Boot 2.4+. */
    const val ON_PROFILE_KEY = "spring.config.activate.on-profile"
}
