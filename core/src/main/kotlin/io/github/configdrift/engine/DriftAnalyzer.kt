package io.github.configdrift.engine

import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigSnapshot
import io.github.configdrift.model.Finding
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.parser.SecretHit
import io.github.configdrift.spi.ContractCatalog
import io.github.configdrift.spi.KeyContract

/**
 * Everything an analyzer is allowed to look at. Analyzers never touch PSI, VFS, or the project
 * tree — they see the parsed snapshot only, which keeps them unit-testable without an IDE
 * fixture.
 */
class AnalysisContext(
    val snapshot: ConfigSnapshot,
    /** Produced during parsing; already masked. */
    val secretHits: List<SecretHit>,
    private val contracts: ContractCatalog = ContractCatalog.EMPTY,
    private val overlayOverrides: OverlayOverrides = OverlayOverrides.NONE,
) {
    /**
     * True when no provider declares anything, i.e. the project has no
     * `spring-configuration-metadata.json`. Feature 8 stays silent in that case rather than
     * reporting every key as undeclared.
     */
    val hasContracts: Boolean get() = contracts.hasContracts

    val declaredKeys: Set<NormalizedKey> get() = contracts.declaredKeys

    fun contractFor(key: NormalizedKey): KeyContract? = contracts.contractFor(key)

    /** Profiles other than `default`; most comparisons only make sense across these. */
    val comparableProfiles: List<ProfileId> =
        snapshot.profileIds.filter { it != ProfileId.DEFAULT }

    /**
     * Per-profile, per-domain overlay verdicts.
     *
     * A profile that sets very few keys of one config system is *usually* an overlay for that
     * system — but the same profile may still be a complete environment in another system.
     * [OverlayHeuristic.classifyByDomain] walks each [ConfigDomain] separately; [isOverlayFor]
     * applies that distinction when MissingKey skips profiles.
     *
     * Manual overrides still win, and every exclusion is reported by [OverlayProfileAnalyzer]
     * rather than applied silently.
     */
    val overlayByDomain: Map<ProfileId, Map<ConfigDomain, OverlayHeuristic.Verdict>> by lazy {
        OverlayHeuristic.classifyByDomain(
            profiles = comparableProfiles.mapNotNull { snapshot.profile(it) },
            manualComplete = overlayOverrides.manualComplete,
            manualOverlay = overlayOverrides.manualOverlay,
        )
    }

    /**
     * True when [profile] should be skipped for MissingKey on [key].
     *
     * The profile must be an overlay for **every** domain of [key] that it actually uses — so a
     * Compose-only overlay still participates in `.env` comparisons for the same profile name.
     */
    fun isOverlayFor(profile: ProfileId, key: NormalizedKey): Boolean {
        val keyDomains = snapshot.domainsByKey[key] ?: return false
        val profileDomains = snapshot.profile(profile)?.domains ?: return false
        val relevant = keyDomains.intersect(profileDomains)
        if (relevant.isEmpty()) return false
        val overlays = overlayByDomain[profile] ?: return false
        return relevant.all { it in overlays }
    }

    /**
     * Keys that are a value in one profile and a nested object in another. Shared state, because
     * [ShapeMismatchAnalyzer] reports them and [MissingKeyAnalyzer] must stay quiet about them to
     * avoid describing one fault three times.
     */
    val structurallyConflictingKeys: Set<NormalizedKey> by lazy {
        StructuralConflict.detect(snapshot.profiles.associate { it.profile to it.keys })
    }

    /** A key set in `default` is inherited by every profile, so it is not "missing" anywhere. */
    fun isSetInDefault(key: NormalizedKey): Boolean =
        snapshot.profile(ProfileId.DEFAULT)?.byKey?.containsKey(key) == true
}

interface DriftAnalyzer {
    val id: String

    fun analyze(context: AnalysisContext): List<Finding>
}
