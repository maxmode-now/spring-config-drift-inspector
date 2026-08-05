package io.github.configdrift.ui

import io.github.configdrift.model.CellState
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import javax.swing.table.AbstractTableModel

/**
 * Feature 2: the per-profile presence table.
 *
 * The engine has always produced [DriftReport.matrix]; until now nothing rendered it, so the
 * profile list was invisible in the IDE even though it drove every comparison. Symbols match the
 * Markdown report's legend so the two views read the same way.
 */
class MatrixTableModel : AbstractTableModel() {

    private var profiles: List<ProfileId> = emptyList()
    private var rows: List<Pair<NormalizedKey, Map<ProfileId, CellState>>> = emptyList()

    fun setReport(report: DriftReport) {
        profiles = report.profiles
        rows = report.matrix.entries
            .sortedBy { it.key.value }
            .map { it.key to it.value }
        // Column count changes with the profile set, so the structure — not just the data —
        // has to be invalidated.
        fireTableStructureChanged()
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 1 + profiles.size

    override fun getColumnName(column: Int): String =
        if (column == 0) "Key" else profiles[column - 1].name

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val (key, cells) = rows.getOrNull(rowIndex) ?: return ""
        if (columnIndex == 0) return key.value

        val profile = profiles.getOrNull(columnIndex - 1) ?: return ""
        return when (cells[profile]) {
            CellState.SET -> SET
            CellState.INHERITED_FROM_DEFAULT -> INHERITED
            else -> MISSING
        }
    }

    /**
     * The cell symbols, shared rather than repeated: the legend above the table and the
     * "only rows with a gap" filter both have to agree with what [getValueAt] actually renders,
     * and three separate copies of `"-"` would drift apart silently.
     */
    companion object {
        const val SET = "O"
        const val INHERITED = "^"
        const val MISSING = "-"

        const val LEGEND =
            "Legend: $SET set  ·  $INHERITED inherited from default  ·  $MISSING missing"
    }
}
