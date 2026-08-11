package io.github.configdrift.secrets

import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.parser.Placeholders

/**
 * A positive verdict, carrying the exact text that is committed to the repository.
 *
 * [committedValue] matters for masking: for `${DB_PASSWORD:changeme}` the committed secret is
 * `changeme`, not the 23-character placeholder expression. Reporting the expression's length made
 * the masked hint misleading. The same materialization applies inside larger strings (e.g. a JDBC
 * URL whose user/password segments are placeholders).
 */
data class SecretMatch(
    val rule: SecretRule,
    val committedValue: String,
)

/**
 * Decides whether a key/value pair exposes a credential.
 *
 * Runs inside the parser so that a positive verdict prevents the plaintext from being retained
 * at all. The detector therefore sees plaintext; nothing after it does.
 */
class SecretDetector(rules: List<SecretRule> = SecretRules.DEFAULTS) {

    /**
     * Value-pattern rules are evaluated first so attribution names the specific credential
     * format. `app.api-key: AKIA...` should be reported as `aws-access-key-id`, not as the
     * generic `api-key` key-name rule — otherwise suppressing the noisy generic rule would also
     * suppress real AWS key detection.
     */
    private val orderedRules: List<SecretRule> = rules.sortedBy { it.valuePattern == null }

    fun detect(key: NormalizedKey, rawValue: String?): SecretMatch? {
        val value = rawValue?.trim().orEmpty()
        if (value.isBlank()) return null

        // What gets committed to the repository is the only thing worth flagging — including
        // placeholder defaults embedded in a larger string (JDBC URLs, etc.).
        val committed = Placeholders.materializeCommitted(value) ?: return null

        val rule = orderedRules.firstOrNull { rule ->
            val keyMatches = rule.keyPattern?.containsMatchIn(key.value) ?: false
            val valueMatches = rule.valuePattern?.containsMatchIn(committed) ?: false
            when {
                rule.keyPattern != null && rule.valuePattern != null -> keyMatches && valueMatches
                rule.keyPattern != null -> keyMatches
                else -> valueMatches
            }
        } ?: return null

        return SecretMatch(rule, committed)
    }
}
