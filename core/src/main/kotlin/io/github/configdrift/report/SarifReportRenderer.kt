package io.github.configdrift.report

import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Finding
import io.github.configdrift.model.MetadataContractMismatch
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.OverlayProfileExcluded
import io.github.configdrift.model.SecretExposure
import io.github.configdrift.model.Severity
import io.github.configdrift.model.ShapeMismatch
import io.github.configdrift.model.SourceLocation
import io.github.configdrift.model.UnresolvedPlaceholder

/**
 * SARIF 2.1.0 report for GitHub Code Scanning (and other SARIF consumers).
 *
 * Hand-built JSON like [JsonReportRenderer]: no extra dependency, and only already-masked
 * [DriftReport] content is emitted. [ruleId] values match the JSON report's `type` field.
 */
class SarifReportRenderer : ReportRenderer {

    override val id: String = "sarif"
    override val fileExtension: String = "sarif"

    override fun render(report: DriftReport): String {
        val rules = report.findings
            .map { typeOf(it) }
            .distinct()
            .sorted()

        return buildString {
            appendLine("{")
            appendLine("""  "${"$"}schema": ${quote(SCHEMA)},""")
            appendLine("""  "version": "2.1.0",""")
            appendLine("""  "runs": [""")
            appendLine("    {")
            appendLine("""      "tool": {""")
            appendLine("""        "driver": {""")
            appendLine("""          "name": "config-drift",""")
            appendLine("""          "informationUri": ${quote(INFORMATION_URI)},""")
            appendLine("""          "rules": [""")
            appendLine(
                rules.joinToString(",\n") { ruleId ->
                    """            {"id": ${quote(ruleId)}, "shortDescription": {"text": ${quote(ruleDescription(ruleId))}}}"""
                },
            )
            appendLine("          ]")
            appendLine("        }")
            appendLine("      },")
            appendLine("""      "results": [""")
            appendLine(
                report.findings.joinToString(",\n") { renderResult(it) },
            )
            appendLine("      ]")
            appendLine("    }")
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun renderResult(finding: Finding): String {
        val fields = mutableListOf(
            """"ruleId": ${quote(typeOf(finding))}""",
            """"level": ${quote(levelOf(finding.severity))}""",
            """"message": {"text": ${quote(finding.message)}}""",
        )
        finding.location?.let { fields += """"locations": [${renderLocation(it)}]""" }
        return "        {" + fields.joinToString(", ") + "}"
    }

    private fun renderLocation(location: SourceLocation): String {
        val uri = location.filePath.replace('\\', '/')
        return """{"physicalLocation": {"artifactLocation": {"uri": ${quote(uri)}}, "region": {"startLine": ${location.line.coerceAtLeast(1)}}}}"""
    }

    private fun levelOf(severity: Severity): String = when (severity) {
        Severity.ERROR -> "error"
        Severity.WARNING -> "warning"
        Severity.INFO -> "note"
    }

    private fun typeOf(finding: Finding): String = when (finding) {
        is MissingKey -> "MissingKey"
        is ShapeMismatch -> "ShapeMismatch"
        is SecretExposure -> "SecretExposure"
        is UnresolvedPlaceholder -> "UnresolvedPlaceholder"
        is MetadataContractMismatch -> "MetadataContractMismatch"
        is OverlayProfileExcluded -> "OverlayProfileExcluded"
    }

    private fun ruleDescription(ruleId: String): String = when (ruleId) {
        "MissingKey" -> "Configuration key missing from one or more profiles"
        "ShapeMismatch" -> "Configuration value shape differs across profiles"
        "SecretExposure" -> "Possible secret committed in configuration"
        "UnresolvedPlaceholder" -> "Placeholder not supplied in the repository"
        "MetadataContractMismatch" -> "Mismatch with spring-configuration-metadata contract"
        "OverlayProfileExcluded" -> "Profile treated as a partial overlay"
        else -> ruleId
    }

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

    private companion object {
        const val SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"
        const val INFORMATION_URI =
            "https://github.com/maxmode-now/spring-config-drift-inspector"
    }
}
