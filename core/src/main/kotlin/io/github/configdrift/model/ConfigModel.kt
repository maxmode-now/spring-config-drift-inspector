package io.github.configdrift.model

import java.security.MessageDigest

/**
 * A Spring profile. [DEFAULT] represents plain `application.yml` / `application.properties`
 * with no profile suffix and no `spring.config.activate.on-profile` marker.
 */
@JvmInline
value class ProfileId(val name: String) : Comparable<ProfileId> {
    override fun compareTo(other: ProfileId): Int = name.compareTo(other.name)

    override fun toString(): String = name

    companion object {
        val DEFAULT = ProfileId("default")
    }
}

/**
 * A key identifying one config property, comparable across profiles.
 *
 * Always build these through a named normalizer, never the constructor directly, so each format's
 * normalization rule lives in exactly one place:
 *  - [io.github.configdrift.parser.KeyNormalizer] for Spring's relaxed-binding form, where
 *    `my-prop`, `myProp`, and `MY_PROP` collapse to one comparable identity.
 *  - [io.github.configdrift.parser.DotenvKeyNormalizer] for `.env` files, which is a no-op
 *    beyond trimming — POSIX env vars are case-sensitive with no relaxed-binding ambiguity to
 *    reconcile, so normalizing them the Spring way would cause false collisions instead of
 *    resolving real ones (`DB_HOST` and `DBHOST` are different variables).
 */
@JvmInline
value class NormalizedKey(val value: String) : Comparable<NormalizedKey> {
    override fun compareTo(other: NormalizedKey): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

/** Where a finding came from. Deliberately PSI-free so it survives into serialized reports. */
data class SourceLocation(
    /** Project-relative path when it can be computed, absolute otherwise. */
    val filePath: String,
    /** 1-based, for display. */
    val line: Int,
    /** Character offset in the file, used to open an editor at the exact element. */
    val offset: Int,
)

/** Coarse type/structure classification — enough for drift detection, not a Binder model. */
enum class ValueShape {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    NULL,
    LIST,
    MAP,
    ;

    val isScalar: Boolean get() = this != LIST && this != MAP
}

/**
 * The value of a config entry.
 *
 * The two-case split is the enforcement point for the masking guarantee: once
 * [io.github.configdrift.secrets.SecretDetector] classifies a key as secret-bearing, the parser
 * stores [Redacted] and the plaintext is dropped before it ever reaches the engine, the UI, or a
 * report. [Redacted.digest] still allows cross-profile equality comparison, so we can report
 * "the prod secret differs from dev" without holding either one.
 */
sealed interface ConfigValue {
    val shape: ValueShape

    data class Plain(val text: String?, override val shape: ValueShape) : ConfigValue

    data class Redacted(
        override val shape: ValueShape,
        /** Truncated SHA-256 of the plaintext; comparable, not reversible. */
        val digest: String,
        val length: Int,
    ) : ConfigValue

    /** Rendered form, safe to show anywhere. */
    fun display(): String = when (this) {
        is Plain -> text ?: "null"
        is Redacted -> "*".repeat(length.coerceIn(1, 12)) + " (${shape.name.lowercase()}, len=$length)"
    }

    /** Comparable identity of the value, usable for both cases. */
    fun comparisonToken(): String = when (this) {
        is Plain -> text ?: " null"
        is Redacted -> "sha256:$digest"
    }

    companion object {
        fun digestOf(plaintext: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(plaintext.toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }

        fun redact(plaintext: String, shape: ValueShape): Redacted =
            Redacted(shape, digestOf(plaintext), plaintext.length)
    }
}

/**
 * Which configuration system an entry came from.
 *
 * Absence only means something *within* one domain. `.env`'s `DB_HOST` and Spring's
 * `spring.datasource.url` are not two spellings of one setting — they are separate systems that
 * happen to share a repository, so "this Spring key is not in your `.env` files" is not a finding,
 * it is a category error. Comparisons that infer absence ([io.github.configdrift.engine.MissingKeyAnalyzer],
 * the key matrix) are therefore scoped to profiles that actually use the key's own domain.
 *
 * Comparisons that only describe keys where they *are* set — shape drift, structural conflicts —
 * need no such scoping: they never claim a key is missing from anywhere.
 */
enum class ConfigDomain {
    SPRING,
    DOTENV,
    DOCKER_COMPOSE,
    ;

    /** Short label for findings and reports. */
    val displayName: String
        get() = when (this) {
            SPRING -> "Spring"
            DOTENV -> ".env"
            DOCKER_COMPOSE -> "Docker Compose"
        }
}

/** One flattened key/value pair as it appears in one file, in one profile. */
data class ConfigEntry(
    val key: NormalizedKey,
    /** The key exactly as written, for display and for jump-to-source. */
    val rawKey: String,
    val value: ConfigValue,
    val profile: ProfileId,
    val location: SourceLocation,
    val domain: ConfigDomain,
) {
    val shape: ValueShape get() = value.shape
}

/**
 * Every entry discovered for one profile. A profile can be assembled from several sources
 * (`application.yml` + `application-prod.yml` + a `---` document inside either), so entries are
 * kept as a list rather than a map; a key repeated across those sources is not itself reported as
 * a finding — [byKey] resolves it last-wins, mirroring Spring's own override order.
 */
data class ProfileSnapshot(
    val profile: ProfileId,
    val entries: List<ConfigEntry>,
) {
    /** Last-wins, mirroring Spring's own override order within a profile. */
    val byKey: Map<NormalizedKey, ConfigEntry> =
        entries.associateBy { it.key }

    val keys: Set<NormalizedKey> get() = byKey.keys

    /**
     * The config systems that contributed to this profile — `staging` assembled from
     * `.env.staging` and `docker-compose.staging.yml` carries both. A profile that no file of a
     * given domain contributed to cannot meaningfully be "missing" that domain's keys.
     */
    val domains: Set<ConfigDomain> = entries.mapTo(mutableSetOf()) { it.domain }
}

/** The full parsed picture of a project's configuration, the engine's only input. */
data class ConfigSnapshot(
    val profiles: List<ProfileSnapshot>,
) {
    val profileIds: List<ProfileId> = profiles.map { it.profile }.sorted()

    val allKeys: Set<NormalizedKey> =
        profiles.flatMapTo(linkedSetOf()) { it.keys }

    /**
     * Which domains each key is defined in — normally exactly one, but a name that legitimately
     * exists in two systems keeps both rather than being forced into a single owner.
     */
    val domainsByKey: Map<NormalizedKey, Set<ConfigDomain>> =
        profiles.asSequence()
            .flatMap { it.entries.asSequence() }
            .groupingBy { it.key }
            .fold(emptySet<ConfigDomain>()) { domains, entry -> domains + entry.domain }

    fun profile(id: ProfileId): ProfileSnapshot? = profiles.firstOrNull { it.profile == id }

    /** True when [profile] uses at least one of the config systems [key] is defined in. */
    fun isComparable(key: NormalizedKey, profile: ProfileSnapshot): Boolean {
        val keyDomains = domainsByKey[key] ?: return false
        return profile.domains.any { it in keyDomains }
    }

    /**
     * Domains under which [key] appears in the `default` profile.
     *
     * Reads [ProfileSnapshot.entries] rather than [ProfileSnapshot.byKey]: the same normalized
     * name can legitimately exist in two systems' default files (e.g. Spring `APP_NAME` and a
     * bare `.env` `APP_NAME`), and last-wins would hide one of them.
     */
    fun defaultDomainsOf(key: NormalizedKey): Set<ConfigDomain> {
        val default = profile(ProfileId.DEFAULT) ?: return emptySet()
        return default.entries.asSequence()
            .filter { it.key == key }
            .mapTo(mutableSetOf()) { it.domain }
    }

    /**
     * True when `default` supplies [key] through a config system [profile] actually uses —
     * Spring `application.yml` inherits into Spring `prod`, but not into an `.env`-only
     * `staging`. Used by the key matrix so those cells render as [CellState.NOT_APPLICABLE]
     * (`~`) rather than [CellState.INHERITED_FROM_DEFAULT] (`^`).
     */
    fun isInheritedFromDefault(key: NormalizedKey, profile: ProfileSnapshot): Boolean =
        defaultDomainsOf(key).any { it in profile.domains }

    /**
     * Presence of [key] under [profileId] for the comparison matrix.
     *
     * Order matters: same-system inheritance (`^`) only after ruling out an explicit set value,
     * and cross-system cells become `~` rather than looking inherited from another system's
     * default file.
     */
    fun matrixCell(key: NormalizedKey, profileId: ProfileId): CellState {
        val snapshot = profile(profileId)
        val isSet = snapshot?.byKey?.containsKey(key) == true
        return when {
            isSet -> CellState.SET
            snapshot != null && isInheritedFromDefault(key, snapshot) ->
                CellState.INHERITED_FROM_DEFAULT
            snapshot == null || !isComparable(key, snapshot) -> CellState.NOT_APPLICABLE
            else -> CellState.MISSING
        }
    }
}
