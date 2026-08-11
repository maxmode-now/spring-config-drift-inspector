package io.github.configdrift.secrets

/**
 * A secret-detection rule. A rule fires when the key matches [keyPattern] (if set) *and* the
 * value matches [valuePattern] (if set); a rule with only one of the two fires on that one.
 */
data class SecretRule(
    val id: String,
    val keyPattern: Regex? = null,
    val valuePattern: Regex? = null,
)

/**
 * The MVP rule set: deliberately small and high-precision.
 *
 * This is not meant to become a hand-rolled secret scanner. The intent is to keep the rule
 * shape compatible with established rule sets (gitleaks-style id + regex pairs) so a curated
 * rule set can be imported later rather than reinvented one false positive at a time.
 */
object SecretRules {

    private fun key(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /**
     * Optional separator between the two halves of a compound word, e.g. `access[SEP]key`
     * matches `accesskey` (Spring, hyphens/underscores already stripped by `KeyNormalizer`) *and*
     * `ACCESS_KEY` (dotenv, which bypasses `KeyNormalizer` — see [io.github.configdrift.parser.DotenvKeyNormalizer]
     * — so its separator survives as a literal character in the middle of the word).
     */
    private const val SEP = "[._-]?"

    val DEFAULTS: List<SecretRule> = listOf(
        // Key-name driven. These carry most of the value in practice, because config keys are
        // conventional: anything named *.password (or *_PASSWORD) is a credential slot.
        //
        // The leading `(^|[._-])` boundary and the `SEP` inside compound words both matter for
        // the same reason: Spring's `KeyNormalizer` collapses `api-key`/`API_KEY` down to one
        // contiguous `apikey` before these rules ever see it, but dotenv keys (`API_KEY`) are
        // matched verbatim, so the separator is still there — both as the word boundary before
        // the match and, for compound words, in the middle of it.
        SecretRule(
            id = "password",
            keyPattern = key("""(^|[._-])(password|passwd|pwd)$"""),
        ),
        SecretRule(
            id = "secret",
            keyPattern = key("""(^|[._-])(secret|client${SEP}secret|secret${SEP}key)$"""),
        ),
        SecretRule(
            id = "token",
            keyPattern = key(
                """(^|[._-])(token|access${SEP}token|refresh${SEP}token|auth${SEP}token)$""",
            ),
        ),
        // Enumerated rather than `...key$`: the loose form also matched innocuous names like
        // `cache-key` and `partition-key`.
        SecretRule(
            id = "api-key",
            keyPattern = key(
                """(^|[._-])(api${SEP}key|access${SEP}key|private${SEP}key|credentials)$""",
            ),
        ),

        // Value driven. These fire regardless of key name, catching credentials parked under
        // an innocuous key.
        SecretRule(
            id = "private-key",
            valuePattern = Regex("""-----BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"""),
        ),
        SecretRule(
            id = "aws-access-key-id",
            valuePattern = Regex("""\b(A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16}\b"""),
        ),
        SecretRule(
            id = "jwt",
            valuePattern = Regex("""\bey[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}\b"""),
        ),
        SecretRule(
            id = "url-embedded-credentials",
            valuePattern = Regex("""\b[a-z][a-z0-9+.-]*://[^\s:/@]+:[^\s:/@]+@"""),
        ),
    )
}
