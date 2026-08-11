package io.github.configdrift.model

enum class Severity { ERROR, WARNING, INFO }

/**
 * The single output currency of the analysis pipeline. Every analyzer produces these and
 * nothing else; the UI and both report renderers consume these and nothing else.
 */
sealed interface Finding {
    val severity: Severity

    /** Null for findings about the analysis itself rather than about one key. */
    val key: NormalizedKey?

    /** Where to navigate. Null only when the finding is about an *absence*. */
    val location: SourceLocation?

    /** One-line, already safe to display (never contains a secret value). */
    val message: String

    /**
     * Whether the user may dismiss this finding permanently.
     *
     * True for everything except [SecretExposure]. A committed credential is not the kind of
     * warning that can be "expected" — the fix is to externalize the value, not to agree to stop
     * being told about it. Refusing suppression here also closes a leak the masking guarantee
     * would otherwise miss: suppressions are persisted to a project file, and the stored
     * identifier names the key and profile, so agreeing to ignore a secret would commit a map of
     * where the hardcoded credentials are.
     */
    val suppressible: Boolean get() = true
}

/**
 * A key present in at least one profile but absent from others.
 *
 * One finding per key, not per (key, profile) pair. Emitting a row for every affected profile
 * turned a single mistake into three or four table rows — a typo'd key produced one row per
 * environment that (correctly) did not have the typo — which buried unrelated findings.
 */
data class MissingKey(
    override val key: NormalizedKey,
    val missingIn: List<ProfileId>,
    val presentIn: List<ProfileId>,
    /** A site where the key *does* exist, so the user has somewhere to jump to. */
    override val location: SourceLocation,
) : Finding {
    override val severity: Severity = Severity.ERROR
    override val message: String =
        "'$key' is missing in ${missingIn.joinToString(", ")} " +
            "but set in ${presentIn.joinToString(", ")}"
}

/** The same key resolves to a different type or structure across profiles. */
data class ShapeMismatch(
    override val key: NormalizedKey,
    val occurrences: List<Occurrence>,
) : Finding {
    data class Occurrence(
        val profile: ProfileId,
        val shape: ValueShape,
        val location: SourceLocation,
    )

    override val severity: Severity = Severity.ERROR
    override val location: SourceLocation? = occurrences.firstOrNull()?.location
    override val message: String =
        "'$key' has inconsistent shape: " +
            occurrences.joinToString(", ") { "${it.profile}=${it.shape.name.lowercase()}" }
}

/**
 * A credential-looking value in a config file.
 *
 * Carries no plaintext by construction — [masked] is produced by
 * [io.github.configdrift.secrets.Masker] at detection time.
 */
data class SecretExposure(
    override val key: NormalizedKey,
    val profile: ProfileId,
    override val location: SourceLocation,
    /** Id of the rule that fired, so users can suppress a noisy rule. */
    val ruleId: String,
    val masked: String,
) : Finding {
    override val severity: Severity = Severity.ERROR
    override val message: String = "Possible $ruleId in '$key' ($profile): $masked"

    override val suppressible: Boolean = false
}

/**
 * A `${...}` reference nothing in the project supplies.
 *
 * Never ERROR: the plugin cannot see the real deployment environment, so this is always a
 * heuristic rather than a fact. The [kind] split exists because the two cases are genuinely
 * different claims, and treating them alike made the plugin warn about the very pattern it is
 * supposed to encourage — `password: ${DB_PASSWORD}` is correct externalization, not a defect.
 */
data class UnresolvedPlaceholder(
    override val key: NormalizedKey,
    val profile: ProfileId,
    override val location: SourceLocation,
    val placeholder: String,
    val hasDefault: Boolean,
    val kind: Kind,
) : Finding {
    enum class Kind {
        /**
         * A dotted, lower-case name that looks like another config key but matches none —
         * usually a typo, and actionable inside the repository.
         */
        INTERNAL_REFERENCE,

        /**
         * An `UPPER_SNAKE_CASE` name, i.e. a deployment environment variable. Not a defect;
         * reported at INFO so the set of required env vars is visible as a deploy checklist.
         */
        EXTERNAL_ENVIRONMENT,
    }

    override val severity: Severity = when (kind) {
        Kind.INTERNAL_REFERENCE -> Severity.WARNING
        Kind.EXTERNAL_ENVIRONMENT -> Severity.INFO
    }

    override val message: String = when (kind) {
        Kind.INTERNAL_REFERENCE ->
            "'\${$placeholder}' referenced by '$key' ($profile) matches no key in this project"
        Kind.EXTERNAL_ENVIRONMENT ->
            "'$key' ($profile) requires '$placeholder' from the deployment environment"
    }
}

/** A disagreement between the config files and `spring-configuration-metadata.json`. */
data class MetadataContractMismatch(
    override val key: NormalizedKey,
    val kind: Kind,
    override val location: SourceLocation?,
    val profile: ProfileId?,
    val declaredType: String?,
    val actualShape: ValueShape?,
) : Finding {
    enum class Kind {
        /** Metadata declares the property, no profile sets it. Informational by nature. */
        DECLARED_NOT_SET,

        /** A profile sets a property the metadata does not declare — likely a typo. */
        SET_NOT_DECLARED,

        /** Declared type and observed shape cannot match, e.g. `java.lang.Integer` vs `"abc"`. */
        TYPE_MISMATCH,
    }

    override val severity: Severity = when (kind) {
        Kind.DECLARED_NOT_SET -> Severity.INFO
        Kind.SET_NOT_DECLARED -> Severity.WARNING
        Kind.TYPE_MISMATCH -> Severity.ERROR
    }

    override val message: String = when (kind) {
        Kind.DECLARED_NOT_SET -> "'$key' is declared in metadata but never set"
        Kind.SET_NOT_DECLARED -> "'$key' is set in '$profile' but not declared in metadata"
        Kind.TYPE_MISMATCH ->
            "'$key' is declared as $declaredType but '$profile' supplies " +
                "${actualShape?.name?.lowercase()}"
    }
}

/**
 * A profile was judged to be a partial overlay *for one config system* and left out of
 * missing-key comparison for that system's keys.
 *
 * Reported rather than applied silently, and this is the whole point of the finding: a profile
 * that sets very few keys is *usually* an overlay meant to be activated alongside another
 * (`SPRING_PROFILES_ACTIVE=prod,local`), but it could equally be a real environment that is
 * missing most of its configuration — which is exactly the defect this plugin exists to catch.
 * Since the files themselves cannot distinguish the two, the guess is made visible so the user
 * can overrule it.
 *
 * Exclusion is per [domain]: a profile that is sparse only in Docker Compose stays in play for
 * `.env` MissingKey checks, and vice versa.
 */
data class OverlayProfileExcluded(
    val profile: ProfileId,
    val domain: ConfigDomain,
    val keyCount: Int,
    val typicalKeyCount: Int,
    /** True when the user marked this profile as an overlay in settings, rather than a guess. */
    val manual: Boolean,
) : Finding {
    override val severity: Severity = Severity.INFO
    override val key: NormalizedKey? = null
    override val location: SourceLocation? = null
    override val message: String = if (manual) {
        "Profile '$profile' is marked as a partial overlay in Config Drift settings, so it is " +
            "excluded from missing-key comparison for ${domain.displayName} keys"
    } else {
        "Profile '$profile' sets $keyCount of ~$typicalKeyCount ${domain.displayName} keys and " +
            "was treated as a partial overlay, so it is excluded from missing-key comparison " +
            "for that system (override this in Settings | Tools | Config Drift)"
    }
}

/**
 * Presence of one key in one profile, for the comparison matrix (feature 2).
 *
 * [NOT_APPLICABLE] is distinct from [MISSING] on purpose: a Spring-only key showing `-` under a
 * profile that has no Spring files at all reads as a gap the user should close, when in fact
 * there is nothing to close — see [ConfigDomain].
 */
enum class CellState { SET, MISSING, INHERITED_FROM_DEFAULT, NOT_APPLICABLE }

/** The complete analysis result: the matrix for the table plus the flat finding list. */
data class DriftReport(
    val projectName: String,
    val generatedAtEpochMillis: Long,
    val profiles: List<ProfileId>,
    val matrix: Map<NormalizedKey, Map<ProfileId, CellState>>,
    /** What the user sees — suppressed findings are already filtered out of this list. */
    val findings: List<Finding>,
    /** Filtered out of [findings] by user choice, kept here so suppression stays reversible. */
    val suppressedFindings: List<Finding> = emptyList(),
) {
    /**
     * Every [Severity], even ones with zero findings.
     *
     * `groupBy` alone would drop a severity entirely when its count is zero — invisible while
     * testing against a fixture that always has all three, but a real difference for a CI script
     * that expects `summary.WARNING` to exist and reads `undefined` instead of `0`.
     */
    fun findingsBySeverity(): Map<Severity, List<Finding>> {
        val grouped = findings.groupBy { it.severity }
        return Severity.entries.associateWith { grouped[it].orEmpty() }
    }
}
