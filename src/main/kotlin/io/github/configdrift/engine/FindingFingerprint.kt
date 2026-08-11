package io.github.configdrift.engine

import io.github.configdrift.model.Finding
import io.github.configdrift.model.MetadataContractMismatch
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.OverlayProfileExcluded
import io.github.configdrift.model.SecretExposure
import io.github.configdrift.model.ShapeMismatch
import io.github.configdrift.model.UnresolvedPlaceholder

/**
 * A stable identity for a finding, used to remember "don't show me this again" across analysis
 * runs.
 *
 * Deliberately excludes [Finding.location]: a line number shifts on almost every edit to the
 * file, and a fingerprint built from it would silently un-suppress a finding the moment someone
 * adds a blank line above it. What survives an edit is the *kind* of problem and where it
 * conceptually lives — the key, the profile, the rule that fired — not the exact offset.
 *
 * [MissingKey], by contrast, deliberately *does* include which profiles the key is missing from.
 * `MissingKeyAnalyzer` groups by key, so one finding can read "missing in prod" today and "missing
 * in prod, stage" after a later edit — a fingerprint of the key alone would treat those as the
 * same finding, so suppressing "missing in prod" would also silently suppress the unrelated new
 * fact that it is now missing from stage too.
 */
object FindingFingerprint {

    fun of(finding: Finding): String = when (finding) {
        is MissingKey ->
            "MissingKey:${finding.key}:${finding.missingIn.map { it.name }.sorted().joinToString(",")}"
        is ShapeMismatch -> "ShapeMismatch:${finding.key}"
        is SecretExposure -> "SecretExposure:${finding.key}:${finding.profile}:${finding.ruleId}"
        is UnresolvedPlaceholder ->
            "UnresolvedPlaceholder:${finding.key}:${finding.profile}:${finding.placeholder}"
        is MetadataContractMismatch ->
            "MetadataContractMismatch:${finding.key}:${finding.kind}:${finding.profile ?: ""}"
        is OverlayProfileExcluded -> "OverlayProfileExcluded:${finding.profile}:${finding.domain}"
    }
}
