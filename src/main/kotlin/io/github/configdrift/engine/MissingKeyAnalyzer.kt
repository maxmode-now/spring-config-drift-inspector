package io.github.configdrift.engine

import io.github.configdrift.model.Finding
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.ProfileId

/**
 * Reports keys that some environments set and others do not.
 *
 * The inheritance rule matters more than the comparison itself: a key set only in
 * `application.yml` is inherited by every profile, so flagging it as "missing in prod" would be
 * wrong and would bury the real findings. A finding is therefore raised only when the key is
 * absent from `default` *and* set in at least one sibling profile.
 */
class MissingKeyAnalyzer : DriftAnalyzer {

    override val id: String = "missing-key"

    override fun analyze(context: AnalysisContext): List<Finding> {
        // Partial overlays are excluded: a profile that deliberately sets three keys would
        // otherwise report every other key in the project as missing from it.
        val profiles = context.completeProfiles
        if (profiles.size < 2) return emptyList()

        val findings = mutableListOf<Finding>()
        val conflicting = context.structurallyConflictingKeys
        for (key in context.snapshot.allKeys) {
            if (context.isSetInDefault(key)) continue
            // A scalar-vs-object conflict is reported once by ShapeMismatchAnalyzer; without this
            // the same fault also shows up as "missing" from both directions.
            if (StructuralConflict.isCoveredBy(key, conflicting)) continue

            val presentIn = mutableListOf<ProfileId>()
            val absentIn = mutableListOf<ProfileId>()
            for (profile in profiles) {
                val snapshot = context.snapshot.profile(profile) ?: continue
                if (snapshot.byKey.containsKey(key)) presentIn += profile else absentIn += profile
            }
            if (presentIn.isEmpty() || absentIn.isEmpty()) continue

            val referenceLocation = context.snapshot.profile(presentIn.first())
                ?.byKey?.get(key)?.location
                ?: continue

            findings += MissingKey(
                key = key,
                missingIn = absentIn.toList(),
                presentIn = presentIn.toList(),
                location = referenceLocation,
            )
        }
        return findings
    }
}
