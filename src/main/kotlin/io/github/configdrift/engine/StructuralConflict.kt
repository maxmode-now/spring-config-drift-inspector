package io.github.configdrift.engine

import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId

/**
 * Finds keys that are a plain value in one environment but a nested object in another.
 *
 * Flattening makes this invisible to ordinary comparison: if `dev` has `app.cache: redis` and
 * `prod` has `app.cache: {host: ...}`, the two profiles contribute the unrelated keys `app.cache`
 * and `app.cache.host`. Left alone, that surfaces as two MissingKey findings pointing in opposite
 * directions — which describes the symptom rather than the fault. It is really one shape conflict,
 * and it is the kind that breaks binding at startup.
 *
 * Kept as a pure function so it is testable without an IDE fixture, like [OverlayHeuristic].
 */
object StructuralConflict {

    /**
     * Keys held as a leaf by at least one profile and as a container by at least one *other*
     * profile. A profile that has both `app.cache` and `app.cache.host` is odd but is not
     * cross-environment drift, so it alone does not make a conflict.
     */
    fun detect(profileKeys: Map<ProfileId, Set<NormalizedKey>>): Set<NormalizedKey> {
        val allKeys = profileKeys.values.flatMapTo(mutableSetOf()) { it }
        val conflicting = mutableSetOf<NormalizedKey>()

        for (key in allKeys) {
            val prefix = key.value + "."
            val leafIn = profileKeys.keys.filterTo(mutableSetOf()) { profile ->
                key in profileKeys.getValue(profile)
            }
            if (leafIn.isEmpty()) continue

            val containerIn = profileKeys.keys.filterTo(mutableSetOf()) { profile ->
                profileKeys.getValue(profile).any { it.value.startsWith(prefix) }
            }
            if (containerIn.isEmpty()) continue

            val leafOnly = leafIn - containerIn
            val containerOnly = containerIn - leafIn
            if (leafOnly.isNotEmpty() && containerOnly.isNotEmpty()) {
                conflicting += key
            }
        }
        return conflicting
    }

    /**
     * True when [key] is one of [conflicting] or lives underneath one.
     *
     * Used to suppress the MissingKey findings that describe the same fault from the other side —
     * reporting `app.cache.host` as "missing in dev" adds nothing once the shape conflict on
     * `app.cache` has been reported.
     */
    fun isCoveredBy(key: NormalizedKey, conflicting: Set<NormalizedKey>): Boolean =
        key in conflicting || conflicting.any { key.value.startsWith(it.value + ".") }
}
