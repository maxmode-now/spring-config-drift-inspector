package io.github.configdrift.report

import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Finding
import io.github.configdrift.model.MetadataContractMismatch
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.OverlayProfileExcluded
import io.github.configdrift.model.SecretExposure
import io.github.configdrift.model.ShapeMismatch
import io.github.configdrift.model.SourceLocation
import io.github.configdrift.model.UnresolvedPlaceholder

/**
 * Feature 10: the machine-readable half, for CI gating.
 *
 * Written by hand for the same reason [io.github.configdrift.metadata.MiniJson] is: no dependency,
 * no version coupling to a library the platform also ships. The output shape is the plugin's
 * public contract, so `type` values must stay stable across releases.
 */
class JsonReportRenderer : ReportRenderer {

    override val id: String = "json"
    override val fileExtension: String = "json"

    override fun render(report: DriftReport): String = buildString {
        appendLine("{")
        appendLine("""  "projectName": ${quote(report.projectName)},""")
        appendLine("""  "generatedAt": ${report.generatedAtEpochMillis},""")
        appendLine("""  "profiles": [${report.profiles.joinToString(", ") { quote(it.name) }}],""")
        appendLine("""  "suppressedCount": ${report.suppressedFindings.size},""")
        appendLine("""  "summary": {""")
        appendLine(
            report.findingsBySeverity()
                .map { (severity, findings) -> """    "${severity.name}": ${findings.size}""" }
                .joinToString(",\n"),
        )
        appendLine("  },")
        appendLine("""  "findings": [""")
        appendLine(report.findings.joinToString(",\n") { renderFinding(it) })
        appendLine("  ]")
        appendLine("}")
    }

    private fun renderFinding(finding: Finding): String {
        val fields = mutableListOf(
            """"type": ${quote(typeOf(finding))}""",
            """"severity": ${quote(finding.severity.name)}""",
            """"key": ${finding.key?.value?.let(::quote) ?: "null"}""",
            """"message": ${quote(finding.message)}""",
            """"location": ${renderLocation(finding.location)}""",
        )
        fields += when (finding) {
            is MissingKey -> listOf(
                """"missingIn": [${finding.missingIn.joinToString(", ") { quote(it.name) }}]""",
                """"presentIn": [${finding.presentIn.joinToString(", ") { quote(it.name) }}]""",
            )
            is ShapeMismatch -> listOf(
                """"occurrences": [${
                    finding.occurrences.joinToString(", ") { occurrence ->
                        """{"profile": ${quote(occurrence.profile.name)}, """ +
                            """"shape": ${quote(occurrence.shape.name)}, """ +
                            """"location": ${renderLocation(occurrence.location)}}"""
                    }
                }]""",
            )
            // No value field exists to emit: the plaintext was discarded at parse time.
            is SecretExposure -> listOf(
                """"profile": ${quote(finding.profile.name)}""",
                """"ruleId": ${quote(finding.ruleId)}""",
                """"masked": ${quote(finding.masked)}""",
            )
            is UnresolvedPlaceholder -> listOf(
                """"kind": ${quote(finding.kind.name)}""",
                """"profile": ${quote(finding.profile.name)}""",
                """"placeholder": ${quote(finding.placeholder)}""",
                """"hasDefault": ${finding.hasDefault}""",
            )
            is MetadataContractMismatch -> listOf(
                """"kind": ${quote(finding.kind.name)}""",
                """"profile": ${finding.profile?.name?.let(::quote) ?: "null"}""",
                """"declaredType": ${finding.declaredType?.let(::quote) ?: "null"}""",
                """"actualShape": ${finding.actualShape?.name?.let(::quote) ?: "null"}""",
            )
            is OverlayProfileExcluded -> listOf(
                """"profile": ${quote(finding.profile.name)}""",
                """"keyCount": ${finding.keyCount}""",
                """"typicalKeyCount": ${finding.typicalKeyCount}""",
                """"manual": ${finding.manual}""",
            )
        }
        return "    {" + fields.joinToString(", ") + "}"
    }

    /**
     * The stable wire name of a finding type. Deliberately hand-mapped rather than derived from
     * the class name, so an internal rename cannot silently break a CI consumer's filter.
     */
    private fun typeOf(finding: Finding): String = when (finding) {
        is MissingKey -> "MissingKey"
        is ShapeMismatch -> "ShapeMismatch"
        is SecretExposure -> "SecretExposure"
        is UnresolvedPlaceholder -> "UnresolvedPlaceholder"
        is MetadataContractMismatch -> "MetadataContractMismatch"
        is OverlayProfileExcluded -> "OverlayProfileExcluded"
    }

    private fun renderLocation(location: SourceLocation?): String =
        location?.let {
            """{"file": ${quote(it.filePath)}, "line": ${it.line}, "offset": ${it.offset}}"""
        } ?: "null"

    private fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
