package io.github.configdrift.engine

import io.github.configdrift.model.CellState
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Finding
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.Severity

/**
 * Runs every analyzer over one snapshot and assembles the report.
 *
 * The analyzer list is a constructor parameter so tests can drive one analyzer in isolation and
 * so a future settings page can disable individual checks without touching this class.
 */
class DriftAnalysisEngine(
    private val analyzers: List<DriftAnalyzer> = defaultAnalyzers(),
) {

    fun analyze(projectName: String, context: AnalysisContext): DriftReport {
        val findings = analyzers.flatMap { it.analyze(context) }

        return DriftReport(
            projectName = projectName,
            generatedAtEpochMillis = System.currentTimeMillis(),
            profiles = context.snapshot.profileIds,
            matrix = buildMatrix(context),
            findings = findings.sortedWith(FINDING_ORDER),
        )
    }

    /** Feature 2: the per-profile presence table the tool window renders. */
    private fun buildMatrix(context: AnalysisContext): Map<NormalizedKey, Map<ProfileId, CellState>> {
        val profiles = context.snapshot.profileIds
        return context.snapshot.allKeys.associateWith { key ->
            profiles.associateWith { profile ->
                val isSet = context.snapshot.profile(profile)?.byKey?.containsKey(key) == true
                when {
                    isSet -> CellState.SET
                    context.isSetInDefault(key) -> CellState.INHERITED_FROM_DEFAULT
                    else -> CellState.MISSING
                }
            }
        }
    }

    companion object {
        fun defaultAnalyzers(): List<DriftAnalyzer> = listOf(
            SecretExposureAnalyzer(),
            MissingKeyAnalyzer(),
            ShapeMismatchAnalyzer(),
            PlaceholderAnalyzer(),
            MetadataContractAnalyzer(),
            OverlayProfileAnalyzer(),
        )

        /** Severity first, then key, so report diffs between runs stay readable. */
        private val FINDING_ORDER: Comparator<Finding> =
            compareBy<Finding> { severityRank(it.severity) }.thenBy { it.key?.value ?: "" }

        private fun severityRank(severity: Severity) = severity.ordinal
    }
}
