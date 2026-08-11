package io.github.configdrift.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mechanism behind cross-system comparison scoping. Without it, a project holding both Spring
 * config and `.env` files reported every key of each system as missing from every profile of the
 * other — 29 ERROR findings on the sample fixture where 17 were real.
 */
class ConfigSnapshotDomainTest {

    private val location = SourceLocation("a.yml", 1, 0)

    private fun entry(key: String, profile: String, domain: ConfigDomain) = ConfigEntry(
        key = NormalizedKey(key),
        rawKey = key,
        value = ConfigValue.Plain("x", ValueShape.STRING),
        profile = ProfileId(profile),
        location = location,
        domain = domain,
    )

    @Test
    fun `a profile's domains are derived from the files that fed it`() {
        val staging = ProfileSnapshot(
            ProfileId("staging"),
            listOf(
                entry("DB_HOST", "staging", ConfigDomain.DOTENV),
                entry("web.DB_HOST", "staging", ConfigDomain.DOCKER_COMPOSE),
            ),
        )
        assertEquals(setOf(ConfigDomain.DOTENV, ConfigDomain.DOCKER_COMPOSE), staging.domains)
    }

    @Test
    fun `a key is only comparable against profiles built from its own config system`() {
        val snapshot = ConfigSnapshot(
            listOf(
                ProfileSnapshot(
                    ProfileId("prod"),
                    listOf(entry("spring.datasource.url", "prod", ConfigDomain.SPRING)),
                ),
                ProfileSnapshot(
                    ProfileId("production"),
                    listOf(entry("DB_HOST", "production", ConfigDomain.DOTENV)),
                ),
            ),
        )

        val springProfile = snapshot.profile(ProfileId("prod"))!!
        val dotenvProfile = snapshot.profile(ProfileId("production"))!!
        val springKey = NormalizedKey("spring.datasource.url")
        val dotenvKey = NormalizedKey("DB_HOST")

        assertTrue(snapshot.isComparable(springKey, springProfile))
        assertTrue(snapshot.isComparable(dotenvKey, dotenvProfile))

        // The regression: neither of these may be reported missing from the other.
        assertFalse(snapshot.isComparable(springKey, dotenvProfile))
        assertFalse(snapshot.isComparable(dotenvKey, springProfile))
    }

    @Test
    fun `a profile sharing one of a key's systems still compares`() {
        // `staging` is fed by both .env and docker-compose; `production` here only by .env. A
        // compose key must still compare between them only where compose is actually present.
        val snapshot = ConfigSnapshot(
            listOf(
                ProfileSnapshot(
                    ProfileId("staging"),
                    listOf(
                        entry("DB_HOST", "staging", ConfigDomain.DOTENV),
                        entry("web.DEBUG", "staging", ConfigDomain.DOCKER_COMPOSE),
                    ),
                ),
                ProfileSnapshot(
                    ProfileId("production"),
                    listOf(entry("DB_HOST", "production", ConfigDomain.DOTENV)),
                ),
            ),
        )

        val composeKey = NormalizedKey("web.DEBUG")
        val dotenvKey = NormalizedKey("DB_HOST")
        val production = snapshot.profile(ProfileId("production"))!!

        assertFalse(snapshot.isComparable(composeKey, production))
        assertTrue(snapshot.isComparable(dotenvKey, production))
    }

    @Test
    fun `a name used by two systems keeps both, and compares against either`() {
        val snapshot = ConfigSnapshot(
            listOf(
                ProfileSnapshot(ProfileId("dev"), listOf(entry("APP_NAME", "dev", ConfigDomain.SPRING))),
                ProfileSnapshot(ProfileId("staging"), listOf(entry("APP_NAME", "staging", ConfigDomain.DOTENV))),
            ),
        )

        val key = NormalizedKey("APP_NAME")
        assertEquals(setOf(ConfigDomain.SPRING, ConfigDomain.DOTENV), snapshot.domainsByKey[key])
        assertTrue(snapshot.isComparable(key, snapshot.profile(ProfileId("dev"))!!))
        assertTrue(snapshot.isComparable(key, snapshot.profile(ProfileId("staging"))!!))
    }

    @Test
    fun `an unknown key is comparable to nothing`() {
        val snapshot = ConfigSnapshot(
            listOf(ProfileSnapshot(ProfileId("dev"), listOf(entry("a.b", "dev", ConfigDomain.SPRING)))),
        )
        assertFalse(
            snapshot.isComparable(NormalizedKey("never.set"), snapshot.profile(ProfileId("dev"))!!),
        )
    }

    @Test
    fun `default inheritance is scoped to the same config system`() {
        val snapshot = ConfigSnapshot(
            listOf(
                ProfileSnapshot(
                    ProfileId.DEFAULT,
                    listOf(entry("spring.application.name", "default", ConfigDomain.SPRING)),
                ),
                ProfileSnapshot(
                    ProfileId("prod"),
                    listOf(entry("spring.datasource.url", "prod", ConfigDomain.SPRING)),
                ),
                ProfileSnapshot(
                    ProfileId("staging"),
                    listOf(entry("DB_HOST", "staging", ConfigDomain.DOTENV)),
                ),
            ),
        )
        val key = NormalizedKey("spring.application.name")
        val prod = snapshot.profile(ProfileId("prod"))!!
        val staging = snapshot.profile(ProfileId("staging"))!!

        assertTrue(snapshot.isInheritedFromDefault(key, prod))
        assertFalse(snapshot.isInheritedFromDefault(key, staging))
        assertEquals(CellState.INHERITED_FROM_DEFAULT, snapshot.matrixCell(key, ProfileId("prod")))
        assertEquals(CellState.NOT_APPLICABLE, snapshot.matrixCell(key, ProfileId("staging")))
    }

    @Test
    fun `cross-system shared name does not inherit Spring default into dotenv profiles`() {
        // Same NormalizedKey in Spring default and dotenv staging: isComparable is true for
        // both systems, so a naive "in default ⇒ ^" would paint dotenv production as inherited
        // from application.yml. Domain-aware inheritance keeps that cell a real gap (`-`).
        val snapshot = ConfigSnapshot(
            listOf(
                ProfileSnapshot(
                    ProfileId.DEFAULT,
                    listOf(entry("APP_NAME", "default", ConfigDomain.SPRING)),
                ),
                ProfileSnapshot(
                    ProfileId("staging"),
                    listOf(entry("APP_NAME", "staging", ConfigDomain.DOTENV)),
                ),
                ProfileSnapshot(
                    ProfileId("production"),
                    listOf(entry("DB_HOST", "production", ConfigDomain.DOTENV)),
                ),
            ),
        )
        val key = NormalizedKey("APP_NAME")
        val production = snapshot.profile(ProfileId("production"))!!

        assertFalse(snapshot.isInheritedFromDefault(key, production))
        assertEquals(CellState.MISSING, snapshot.matrixCell(key, ProfileId("production")))
        assertEquals(CellState.SET, snapshot.matrixCell(key, ProfileId("staging")))
        assertEquals(CellState.SET, snapshot.matrixCell(key, ProfileId.DEFAULT))
    }

    @Test
    fun `default domains keep both systems when the same key appears in each default file`() {
        val snapshot = ConfigSnapshot(
            listOf(
                ProfileSnapshot(
                    ProfileId.DEFAULT,
                    listOf(
                        entry("APP_NAME", "default", ConfigDomain.SPRING),
                        entry("APP_NAME", "default", ConfigDomain.DOTENV),
                    ),
                ),
                ProfileSnapshot(
                    ProfileId("production"),
                    listOf(entry("DB_HOST", "production", ConfigDomain.DOTENV)),
                ),
            ),
        )
        val key = NormalizedKey("APP_NAME")
        assertEquals(
            setOf(ConfigDomain.SPRING, ConfigDomain.DOTENV),
            snapshot.defaultDomainsOf(key),
        )
        assertTrue(snapshot.isInheritedFromDefault(key, snapshot.profile(ProfileId("production"))!!))
        assertEquals(CellState.INHERITED_FROM_DEFAULT, snapshot.matrixCell(key, ProfileId("production")))
    }
}
