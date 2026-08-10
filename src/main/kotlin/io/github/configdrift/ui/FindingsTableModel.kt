package io.github.configdrift.ui

import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Finding
import io.github.configdrift.model.OverlayProfileExcluded
import io.github.configdrift.model.Severity
import javax.swing.table.AbstractTableModel

/**
 * Backs the findings table.
 *
 * Every cell value comes from [Finding.message] or an already-masked field, so the table cannot
 * render a secret value even if a new finding type is added later.
 */
class FindingsTableModel : AbstractTableModel() {

    private var findings: List<Finding> = emptyList()

    fun setReport(report: DriftReport) = setFindings(report.findings)

    /** Used directly for the Suppressed tab, which shows [DriftReport.suppressedFindings]. */
    fun setFindings(newFindings: List<Finding>) {
        findings = newFindings
        fireTableDataChanged()
    }

    fun findingAt(modelRow: Int): Finding? = findings.getOrNull(modelRow)

    override fun getRowCount(): Int = findings.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    // Column 0 is typed as Severity, not String, so a dedicated cell renderer (installed by
    // DriftToolWindowPanel) can draw it as an icon instead of the bare enum name.
    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) Severity::class.java else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val finding = findings.getOrNull(rowIndex) ?: return ""
        return when (columnIndex) {
            0 -> finding.severity
            // "—" rather than blank: a blank cell for a profile-level finding like
            // OverlayProfileExcluded reads as missing data, not as "this isn't about one key."
            1 -> finding.key?.value ?: "—"
            2 -> finding.message
            // OverlayProfileExcluded has no file location, but Jump to Source still does
            // something for it — NavigateAction sends it to Settings | Tools | Config Drift
            // instead. Showing that destination here matters more than it looks: a bare "—"
            // told the user there was nothing to navigate to, when double-clicking the row
            // actually opened Settings. A first-time user has no way to discover that a row
            // marked unreachable is, in fact, reachable — the Location cell is the only place
            // that claim could be corrected.
            3 -> if (finding is OverlayProfileExcluded) {
                "Settings | Tools | Config Drift"
            } else {
                finding.location?.let { "${it.filePath}:${it.line}" } ?: "—"
            }
            else -> ""
        }
    }

    private companion object {
        val COLUMNS = arrayOf("Severity", "Key", "Detail", "Location")
    }
}
