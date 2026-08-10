package io.github.configdrift.engine

import com.intellij.openapi.project.Project
import io.github.configdrift.model.ConfigSnapshot
import io.github.configdrift.model.Finding
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.parser.SecretHit
import io.github.configdrift.settings.ConfigDriftProjectSettings
import io.github.configdrift.spi.BindingContractProvider
import io.github.configdrift.spi.KeyContract

/**
 * Everything an analyzer is allowed to look at. Analyzers never touch PSI, VFS, or the project
 * tree — they see the parsed snapshot only, which keeps them unit-testable without an IDE
 * fixture.
 */
class AnalysisContext(
    val project: Project,
    val snapshot: ConfigSnapshot,
    /** Produced during parsing; already masked. */
    val secretHits: List<SecretHit>,
    private val contractProviders: List<BindingContractProvider>,
) {
    /**
     * True when no provider declares anything, i.e. the project has no
     * `spring-configuration-metadata.json`. Feature 8 stays silent in that case rather than
     * reporting every key as undeclared.
     */
    val hasContracts: Boolean by lazy { declaredKeys.isNotEmpty() }

    val declaredKeys: Set<NormalizedKey> by lazy {
        contractProviders.flatMapTo(mutableSetOf()) { it.declaredKeys(project) }
    }

    fun contractFor(key: NormalizedKey): KeyContract? =
        contractProviders.firstNotNullOfOrNull { it.contractFor(project, key) }

    /** Profiles other than `default`; most comparisons only make sense across these. */
    val comparableProfiles: List<ProfileId> =
        snapshot.profileIds.filter { it != ProfileId.DEFAULT }

    /**
     * Profiles that set so few keys they are almost certainly overlays applied on top of another
     * profile, not standalone environments.
     *
     * Spring allows several profiles to be active at once, so a file setting three keys is a
     * perfectly normal overlay — but it is indistinguishable, from the files alone, from an
     * environment that forgot everything else. The automatic guess ([OverlayHeuristic]) can be
     * overridden per-profile in project settings; either way, every exclusion is reported by
     * [OverlayProfileAnalyzer] rather than applied silently.
     */
    val overlayProfiles: Map<ProfileId, OverlayHeuristic.Verdict> by lazy {
        val counts = comparableProfiles.associateWith { profile ->
            snapshot.profile(profile)?.keys?.size ?: 0
        }
        // manualClassification() hands back a private copy rather than a live reference into
        // settings — this runs on a background analysis thread, while the EDT can be mutating
        // the same sets via Settings|Apply or a suppress/un-suppress action at any moment.
        val classification = ConfigDriftProjectSettings.getInstance(project).manualClassification()
        OverlayHeuristic.classify(counts, classification.manualComplete, classification.manualOverlay)
    }

    /** The profiles missing-key comparison actually runs across. */
    val completeProfiles: List<ProfileId> by lazy {
        comparableProfiles.filterNot { it in overlayProfiles }
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
