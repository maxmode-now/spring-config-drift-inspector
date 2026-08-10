package io.github.configdrift.parser

import io.github.configdrift.model.NormalizedKey

/**
 * Builds a [NormalizedKey] for a `.env` entry.
 *
 * Unlike [KeyNormalizer], this is a no-op beyond trimming: env vars are POSIX-style,
 * case-sensitive, `UPPER_SNAKE_CASE` by convention, with no relaxed-binding ambiguity to
 * reconcile. Running dotenv keys through [KeyNormalizer] would actively cause false collisions —
 * it strips `_` and lowercases, so `DB_HOST` and `DBHOST` would compare equal even though they are
 * different variables. Kept as a named normalizer anyway, not a raw `NormalizedKey(...)`
 * construction, to preserve the codebase's convention that every parser builds keys through one
 * named place — see [NormalizedKey]'s own doc.
 */
object DotenvKeyNormalizer {
    fun normalize(rawKey: String): NormalizedKey = NormalizedKey(rawKey.trim())
}
