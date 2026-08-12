package io.github.configdrift.report

import io.github.configdrift.model.CellState
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Severity
import java.time.Instant

/**
 * Human-readable report output (IDE clipboard copy and CLI `--format markdown`).
 *
 * Set [includeMatrix] to false for PR comments — the key matrix can exceed GitHub's
 * comment size limit on larger projects.
 */
class MarkdownReportRenderer(
    private val includeMatrix: Boolean = true,
) : ReportRenderer {

    override val id: String = "markdown"
    override val fileExtension: String = "md"

    override fun render(report: DriftReport): String = buildString {
        appendLine("# Spring Config Drift — ${report.projectName}")
        appendLine()
        appendLine("Generated: ${Instant.ofEpochMilli(report.generatedAtEpochMillis)}")
        appendLine("Profiles: ${report.profiles.joinToString(", ")}")
        appendLine()

        appendSummary(report)
        appendFindings(report)
        appendSuppressedNote(report)
        if (includeMatrix) appendMatrix(report)
    }

    private fun StringBuilder.appendSummary(report: DriftReport) {
        appendLine("## Summary")
        appendLine()
        appendLine("| Severity | Count |")
        appendLine("| --- | --- |")
        for (severity in Severity.entries) {
            val count = report.findings.count { it.severity == severity }
            appendLine("| ${severity.name} | $count |")
        }
        appendLine()
    }

    private fun StringBuilder.appendFindings(report: DriftReport) {
        appendLine("## Findings")
        appendLine()
        if (report.findings.isEmpty()) {
            appendLine("No drift detected.")
            appendLine()
            return
        }

        appendLine("| Severity | Key | Detail | Location |")
        appendLine("| --- | --- | --- | --- |")
        for (finding in report.findings) {
            val location = finding.location
                ?.let { "${escape(it.filePath)}:${it.line}" }
                ?: "—"
            val key = finding.key?.value?.let { "`${escape(it)}`" } ?: "—"
            appendLine(
                "| ${finding.severity} | $key | ${escape(finding.message)} | $location |",
            )
        }
        appendLine()
    }

    /** Visible, not silent: a suppressed finding is a deliberate choice, not a fixed problem. */
    private fun StringBuilder.appendSuppressedNote(report: DriftReport) {
        if (report.suppressedFindings.isEmpty()) return
        appendLine(
            "_${report.suppressedFindings.size} finding(s) suppressed and not shown above " +
                "— see the Suppressed tab in the Config Drift tool window._",
        )
        appendLine()
    }

    private fun StringBuilder.appendMatrix(report: DriftReport) {
        appendLine("## Key matrix")
        appendLine()
        appendLine(
            "Legend: `O` set · `^` inherited from default · `-` missing · " +
                "`~` not applicable (different config system)",
        )
        appendLine()
        appendLine("| Key | ${report.profiles.joinToString(" | ")} |")
        appendLine("| --- |${report.profiles.joinToString("") { " --- |" }}")

        for ((key, cells) in report.matrix.entries.sortedBy { it.key.value }) {
            val row = report.profiles.joinToString(" | ") { profile ->
                when (cells[profile]) {
                    CellState.SET -> "O"
                    CellState.INHERITED_FROM_DEFAULT -> "^"
                    CellState.NOT_APPLICABLE -> "~"
                    else -> "-"
                }
            }
            appendLine("| `${escape(key.value)}` | $row |")
        }
    }

    /** Pipes would break the table; backslashes must not swallow the following character. */
    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")
}
