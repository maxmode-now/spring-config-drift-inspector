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

    val DEFAULTS: List<SecretRule> = listOf(
        // Key-name driven. These carry most of the value in practice, because Spring config
        // keys are conventional: anything named *.password is a credential slot.
        SecretRule(
            id = "password",
            keyPattern = key("""(^|\.)(password|passwd|pwd)$"""),
        ),
        SecretRule(
            id = "secret",
            keyPattern = key("""(^|\.)(secret|clientsecret|secretkey)$"""),
        ),
        SecretRule(
            id = "token",
            keyPattern = key("""(^|\.)(token|accesstoken|refreshtoken|authtoken)$"""),
        ),
        // Enumerated rather than `...key$`: the loose form also matched innocuous names like
        // `cache-key` and `partition-key`.
        SecretRule(
            id = "api-key",
            keyPattern = key("""(^|\.)(apikey|accesskey|privatekey|credentials)$"""),
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
