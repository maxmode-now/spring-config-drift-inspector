package io.github.configdrift.parser

import io.github.configdrift.model.NormalizedKey

/**
 * Builds a [NormalizedKey] for formats with no relaxed-binding ambiguity to reconcile: `.env`
 * entries, and [io.github.configdrift.parser.DockerComposeConfigParser]'s already-service-qualified
 * `<service>.<ENV_VAR_NAME>` keys.
 *
 * Unlike [KeyNormalizer], this is a no-op beyond trimming: env vars are POSIX-style,
 * case-sensitive, `UPPER_SNAKE_CASE` by convention. Running them through [KeyNormalizer] would
 * actively cause false collisions — it strips `_` and lowercases, so `DB_HOST` and `DBHOST` would
 * compare equal even though they are different variables. Kept as a named normalizer anyway, not a
 * raw `NormalizedKey(...)` construction, to preserve the codebase's convention that every parser
 * builds keys through one named place — see [NormalizedKey]'s own doc.
 */
object DotenvKeyNormalizer {
    fun normalize(rawKey: String): NormalizedKey = NormalizedKey(rawKey.trim())
}
