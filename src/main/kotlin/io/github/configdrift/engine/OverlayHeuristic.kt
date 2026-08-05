package io.github.configdrift.engine

import io.github.configdrift.model.ProfileId

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

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
