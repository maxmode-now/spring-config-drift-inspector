package io.github.configdrift.engine

import io.github.configdrift.model.Finding
import io.github.configdrift.model.SecretExposure

/**
 * Turns the hits recorded during parsing into findings.
 *
 * Detection deliberately does not happen here. By the time the engine runs, plaintext secret
 * values no longer exist anywhere in memory — the parser replaced them with a digest and a
 * length. This analyzer only relabels what the parser already found, which is why no code path
 * from the engine onward is capable of leaking a value.
 */
class SecretExposureAnalyzer : DriftAnalyzer {

    override val id: String = "secret-exposure"

    override fun analyze(context: AnalysisContext): List<Finding> =
        context.secretHits.map { hit ->
            SecretExposure(
                key = hit.key,
                profile = hit.profile,
                location = hit.location,
                ruleId = hit.ruleId,
                masked = hit.masked,
            )
        }
}
