package io.github.configdrift.engine

import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.ProfileSnapshot

/**
 * Decides which profiles are partial overlays, pure and settings-agnostic so it is testable
 * without an IDE fixture.
 *
 * A profile that sets very few keys is *usually* an overlay meant to be activated alongside
 * another (`SPRING_PROFILES_ACTIVE=prod,local`), but the files alone cannot rule out that it is
 * a real environment missing most of its configuration. [manualComplete] and [manualOverlay] —
 * sourced from the user's project settings — let the user resolve that ambiguity explicitly;
 * a manual verdict always wins over the automatic guess.
 */
object OverlayHeuristic {

    data class Verdict(val keyCount: Int, val typicalKeyCount: Int, val manual: Boolean)

    fun classify(
        keyCounts: Map<ProfileId, Int>,
        manualComplete: Set<String>,
        manualOverlay: Set<String>,
    ): Map<ProfileId, Verdict> {
        // With fewer than three profiles there is no basis for calling any of them unusual
        // automatically — but an explicit user choice still applies regardless of count.
        val autoEligible = keyCounts.size >= 3
        val typical = median(keyCounts.values.toList())
        val threshold = typical / 4

        return keyCounts.mapNotNull { (profile, count) ->
            val isOverlay = when {
                profile.name in manualComplete -> false
                profile.name in manualOverlay -> true
                autoEligible -> count <= threshold
                else -> false
            }
            if (!isOverlay) return@mapNotNull null
            profile to Verdict(count, typical, manual = profile.name in manualOverlay)
        }.toMap()
    }

    /**
     * Classifies overlays **per config system**, using only that system's key counts.
     *
     * Grouping by exact [io.github.configdrift.model.ProfileSnapshot.domains] set equality was
     * too strict: `staging` with `{DOTENV, DOCKER_COMPOSE}` never peered with `production` that
     * only had `{DOTENV}`, so a sparse multi-domain profile could never be auto-detected.
     * Walking each [ConfigDomain] separately — and counting only entries of that domain — matches
     * the original intent (don't compare a four-variable `.env` against Spring's thirty keys)
     * without that false negative.
     *
     * A profile may be an overlay for Compose while remaining a complete `.env` environment;
     * callers must honor that distinction when excluding profiles from MissingKey.
     */
    fun classifyByDomain(
        profiles: List<ProfileSnapshot>,
        manualComplete: Set<String>,
        manualOverlay: Set<String>,
    ): Map<ProfileId, Map<ConfigDomain, Verdict>> {
        val byId = profiles.associateBy { it.profile }
        val result = mutableMapOf<ProfileId, MutableMap<ConfigDomain, Verdict>>()

        for (domain in ConfigDomain.entries) {
            val peers = profiles.filter { domain in it.domains }.map { it.profile }
            if (peers.isEmpty()) continue

            val counts = peers.associateWith { profileId ->
                byId.getValue(profileId).entries.count { it.domain == domain }
            }
            for ((profile, verdict) in classify(counts, manualComplete, manualOverlay)) {
                result.getOrPut(profile) { mutableMapOf() }[domain] = verdict
            }
        }
        return result
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
