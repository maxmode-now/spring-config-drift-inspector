package io.github.configdrift.secrets

/**
 * Produces the only representation of a secret value that is allowed to leave the parser.
 *
 * Masking is a *detection-time* operation, not a rendering-time one: nothing downstream of
 * [io.github.configdrift.parser.ConfigFileParser] ever receives the plaintext, so there is no
 * code path — tool window, Markdown report, JSON report, log statement — that can leak it by
 * forgetting to mask. See [io.github.configdrift.model.ConfigValue.Redacted].
 */
object Masker {

    private const val MAX_DOTS = 8

    /**
     * Reveals length only. No prefix or suffix characters are kept on purpose: a 4-character
     * prefix is enough to make short credentials guessable, and the length alone is already
     * sufficient for the user to tell two environments' secrets apart on screen.
     */
    fun mask(plaintext: String): String {
        val visibleLength = plaintext.length
        return "•".repeat(visibleLength.coerceIn(1, MAX_DOTS)) + " (len=$visibleLength)"
    }
}
