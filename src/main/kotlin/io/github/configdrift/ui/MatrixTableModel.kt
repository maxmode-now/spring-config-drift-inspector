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

    // getOrNull for the same reason getValueAt uses it: setReport() fires a *structural* change,
    // and Swing rebuilds the column model around that — an index from the pre-change column count
    // reaching this method must degrade to a blank header, not throw out of a paint pass.
    override fun getColumnName(column: Int): String =
        if (column == 0) "Key" else profiles.getOrNull(column - 1)?.name ?: ""

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val (key, cells) = rows.getOrNull(rowIndex) ?: return ""
        if (columnIndex == 0) return key.value

        val profile = profiles.getOrNull(columnIndex - 1) ?: return ""
        return when (cells[profile]) {
            CellState.SET -> SET
            CellState.INHERITED_FROM_DEFAULT -> INHERITED
            CellState.NOT_APPLICABLE -> NOT_APPLICABLE
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

        /**
         * Rendered distinctly from [MISSING] because it means the opposite thing: the key belongs
         * to a config system this profile does not use at all, so there is nothing to fix. The
         * "only keys missing somewhere" filter matches on [MISSING] alone, so these rows correctly
         * stay out of it.
         */
        const val NOT_APPLICABLE = "~"

        const val LEGEND =
            "Legend: $SET set  ·  $INHERITED inherited from default  ·  $MISSING missing  ·  " +
                "$NOT_APPLICABLE not applicable (different config system)"
    }
}
