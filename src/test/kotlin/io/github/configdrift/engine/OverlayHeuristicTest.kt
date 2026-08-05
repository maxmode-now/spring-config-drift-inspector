package io.github.configdrift.engine

import io.github.configdrift.model.ProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayHeuristicTest {

    private fun profiles(vararg pairs: Pair<String, Int>): Map<ProfileId, Int> =
        pairs.associate { (name, count) -> ProfileId(name) to count }

    @Test
    fun `a profile setting far fewer keys than the others is treated as an overlay`() {
        val counts = profiles("dev" to 9, "prod" to 10, "stage" to 7, "local" to 1)
        val verdicts = OverlayHeuristic.classify(counts, manualComplete = emptySet(), manualOverlay = emptySet())

        assertEquals(setOf(ProfileId("local")), verdicts.keys)
        assertTrue(verdicts.getValue(ProfileId("local")).manual.not())
    }

    @Test
    fun `fewer than three profiles is not enough basis to guess`() {
        val counts = profiles("dev" to 9, "local" to 1)
        assertEquals(emptyMap(), OverlayHeuristic.classify(counts, emptySet(), emptySet()))
    }

    @Test
    fun `manual override forces a profile to count as complete despite a low key count`() {
        val counts = profiles("dev" to 9, "prod" to 10, "stage" to 7, "local" to 1)
        val verdicts = OverlayHeuristic.classify(
            counts,
            manualComplete = setOf("local"),
            manualOverlay = emptySet(),
        )
        assertEquals(emptyMap(), verdicts)
    }

    @Test
    fun `manual override forces a profile to count as an overlay despite a normal key count`() {
        val counts = profiles("dev" to 9, "prod" to 10, "stage" to 8)
        val verdicts = OverlayHeuristic.classify(
            counts,
            manualComplete = emptySet(),
            manualOverlay = setOf("stage"),
        )
        assertEquals(setOf(ProfileId("stage")), verdicts.keys)
        assertTrue(verdicts.getValue(ProfileId("stage")).manual)
    }

    @Test
    fun `manual override applies even below the three-profile threshold`() {
        val counts = profiles("dev" to 9, "local" to 1)
        val verdicts = OverlayHeuristic.classify(
            counts,
            manualComplete = emptySet(),
            manualOverlay = setOf("local"),
        )
        assertEquals(setOf(ProfileId("local")), verdicts.keys)
    }

    @Test
    fun `manual complete wins over manual overlay if both are somehow set`() {
        val counts = profiles("dev" to 9, "prod" to 10, "local" to 1)
        val verdicts = OverlayHeuristic.classify(
            counts,
            manualComplete = setOf("local"),
            manualOverlay = setOf("local"),
        )
        assertEquals(emptyMap(), verdicts)
    }
}
