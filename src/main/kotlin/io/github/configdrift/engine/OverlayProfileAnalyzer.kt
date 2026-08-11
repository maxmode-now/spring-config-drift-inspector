package io.github.configdrift.engine

import io.github.configdrift.model.Finding
import io.github.configdrift.model.OverlayProfileExcluded

/**
 * Surfaces every profile×domain that [AnalysisContext] decided to treat as a partial overlay.
 *
 * Exists so the heuristic is never silent. Excluding a profile from missing-key comparison for
 * one config system suppresses findings in that system, and a suppression the user cannot see is
 * worse than the noise it removes — if the guess is wrong, the profile it wrongly excused is a
 * real environment with most of its configuration missing.
 */
class OverlayProfileAnalyzer : DriftAnalyzer {

    override val id: String = "overlay-profile"

    override fun analyze(context: AnalysisContext): List<Finding> =
        context.overlayByDomain.flatMap { (profile, byDomain) ->
            byDomain.map { (domain, verdict) ->
                OverlayProfileExcluded(
                    profile = profile,
                    domain = domain,
                    keyCount = verdict.keyCount,
                    typicalKeyCount = verdict.typicalKeyCount,
                    manual = verdict.manual,
                )
            }
        }
}
