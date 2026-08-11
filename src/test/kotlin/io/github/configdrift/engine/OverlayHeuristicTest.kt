package io.github.configdrift.engine

import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigEntry
import io.github.configdrift.model.ConfigValue
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.ProfileSnapshot
import io.github.configdrift.model.SourceLocation
import io.github.configdrift.model.ValueShape
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

    @Test
    fun `classifyByDomain peers profiles that share a system even when domain sets differ`() {
        // staging has both .env and compose; production/qa are .env-only. Exact set equality
        // would leave staging alone (never auto-overlay); per-domain peers catch it.
        val location = SourceLocation("x", 1, 0)
        fun entry(key: String, profile: String, domain: ConfigDomain) = ConfigEntry(
            key = NormalizedKey(key),
            rawKey = key,
            value = ConfigValue.Plain("v", ValueShape.STRING),
            profile = ProfileId(profile),
            location = location,
            domain = domain,
        )

        val staging = ProfileSnapshot(
            ProfileId("staging"),
            listOf(
                entry("A", "staging", ConfigDomain.DOTENV),
                entry("B", "staging", ConfigDomain.DOTENV),
                entry("web.X", "staging", ConfigDomain.DOCKER_COMPOSE),
            ),
        )
        val production = ProfileSnapshot(
            ProfileId("production"),
            (1..20).map { entry("K$it", "production", ConfigDomain.DOTENV) },
        )
        val qa = ProfileSnapshot(
            ProfileId("qa"),
            (1..18).map { entry("K$it", "qa", ConfigDomain.DOTENV) },
        )

        val byDomain = OverlayHeuristic.classifyByDomain(
            listOf(staging, production, qa),
            manualComplete = emptySet(),
            manualOverlay = emptySet(),
        )

        assertEquals(setOf(ConfigDomain.DOTENV), byDomain[ProfileId("staging")]?.keys)
        assertEquals(2, byDomain.getValue(ProfileId("staging")).getValue(ConfigDomain.DOTENV).keyCount)
        assertTrue(ProfileId("production") !in byDomain)
        assertTrue(ProfileId("qa") !in byDomain)
    }

    @Test
    fun `classifyByDomain can mark a profile overlay in one system only`() {
        val location = SourceLocation("x", 1, 0)
        fun entry(key: String, profile: String, domain: ConfigDomain) = ConfigEntry(
            key = NormalizedKey(key),
            rawKey = key,
            value = ConfigValue.Plain("v", ValueShape.STRING),
            profile = ProfileId(profile),
            location = location,
            domain = domain,
        )

        // staging: rich dotenv + sparse compose; production/qa: rich in both.
        val staging = ProfileSnapshot(
            ProfileId("staging"),
            (1..20).map { entry("E$it", "staging", ConfigDomain.DOTENV) } +
                listOf(entry("web.ONLY", "staging", ConfigDomain.DOCKER_COMPOSE)),
        )
        fun rich(name: String) = ProfileSnapshot(
            ProfileId(name),
            (1..20).map { entry("E$it", name, ConfigDomain.DOTENV) } +
                (1..15).map { entry("web.C$it", name, ConfigDomain.DOCKER_COMPOSE) },
        )

        val byDomain = OverlayHeuristic.classifyByDomain(
            listOf(staging, rich("production"), rich("qa")),
            manualComplete = emptySet(),
            manualOverlay = emptySet(),
        )

        assertEquals(setOf(ConfigDomain.DOCKER_COMPOSE), byDomain[ProfileId("staging")]?.keys)
        assertTrue(ConfigDomain.DOTENV !in byDomain.getValue(ProfileId("staging")))
    }

    @Test
    fun `classifyByDomain does not peer Spring against dotenv`() {
        val location = SourceLocation("x", 1, 0)
        fun entry(key: String, profile: String, domain: ConfigDomain) = ConfigEntry(
            key = NormalizedKey(key),
            rawKey = key,
            value = ConfigValue.Plain("v", ValueShape.STRING),
            profile = ProfileId(profile),
            location = location,
            domain = domain,
        )

        val springProfiles = listOf("dev", "prod", "stage").map { name ->
            ProfileSnapshot(
                ProfileId(name),
                (1..30).map { entry("s.k$it", name, ConfigDomain.SPRING) },
            )
        }
        val dotenv = ProfileSnapshot(
            ProfileId("production"),
            (1..4).map { entry("E$it", "production", ConfigDomain.DOTENV) },
        )

        val byDomain = OverlayHeuristic.classifyByDomain(
            springProfiles + dotenv,
            manualComplete = emptySet(),
            manualOverlay = emptySet(),
        )

        // Four dotenv keys next to Spring thirties must not look like an overlay.
        assertTrue(ProfileId("production") !in byDomain)
        assertTrue(byDomain.values.none { ConfigDomain.DOTENV in it.keys })
    }
}
