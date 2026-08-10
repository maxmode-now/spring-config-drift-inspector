package io.github.configdrift.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.github.configdrift.ConfigDriftService
import io.github.configdrift.settings.ConfigDriftProjectSettings
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.DefaultCellEditor

/**
 * Settings | Tools | Config Drift.
 *
 * Lets a project override [io.github.configdrift.engine.OverlayHeuristic]'s automatic guess about
 * which profiles are partial overlays. The default is always "Auto-detect" — this screen exists
 * to correct the heuristic, not to require setup before the plugin is useful.
 */
class ConfigDriftConfigurable(private val project: Project) : Configurable {

    private enum class Classification(val label: String) {
        AUTO("Auto-detect"),
        COMPLETE("Complete environment"),
        OVERLAY("Partial overlay"),
        ;

        // Swing's default cell renderer and JComboBox both render via toString(), not a getter —
        // without this override the table and dropdown showed "AUTO" / "COMPLETE" / "OVERLAY"
        // instead of the human-readable label.
        override fun toString(): String = label
    }

    private data class Row(val profileName: String, var classification: Classification)

    private inner class RowsTableModel(private val rows: MutableList<Row>) : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Profile" else "Treat as"
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 1
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) String::class.java else Classification::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            if (columnIndex == 0) rows[rowIndex].profileName else rows[rowIndex].classification

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 1 && value is Classification) {
                rows[rowIndex].classification = value
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }

        fun rowsSnapshot(): List<Row> = rows.toList()

        /**
         * Replaces the data in place rather than the model being swapped out from under the
         * table. `JTable.setModel()` rebuilds the column set by default, which silently discards
         * the combo-box cell editor configured in [createComponent] — after that, editing a cell
         * fell back to a plain text field whose String value never matched `is Classification`
         * in [setValueAt], so edits appeared to do nothing.
         */
        fun replaceRows(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            fireTableDataChanged()
        }
    }

    private var tableModel: RowsTableModel? = null
    private var table: JBTable? = null

    override fun getDisplayName(): String = "Config Drift"

    override fun createComponent(): JComponent {
        val rows = loadRows().toMutableList()
        val model = RowsTableModel(rows)
        tableModel = model

        val newTable = JBTable(model)
        newTable.getColumn("Treat as").cellEditor = comboEditor()
        newTable.getColumn("Treat as").cellRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = DefaultTableCellRenderer.LEFT
        }
        table = newTable

        // Shown by JBTable itself when there are no rows, rather than swapping the table out for a
        // label: the previous version added a label *instead of* the table when the profile list
        // was empty, so a user who opened this page before running an analysis kept seeing the
        // placeholder even after reset() refilled the model.
        newTable.emptyText.text =
            "No profiles found yet — run Tools | Analyze Spring Config Drift once."

        val panel = JPanel(BorderLayout())
        val note = JBLabel(
            // A fixed pixel width is deliberate: JLabel's HTML renderer computes preferred size
            // as if the text were one unwrapped line, so it only wraps to the container's actual
            // width if that happens to be narrower — otherwise the line runs off the edge of the
            // settings panel instead of wrapping. Constraining width in the markup itself makes
            // wrapping happen regardless of how the Settings dialog ends up sizing this panel.
            "<html><div style='width: 420px'>Only affects missing-key comparison. Profiles " +
                "left on <b>Auto-detect</b> are judged by how many keys they set relative to " +
                "other profiles — see the INFO findings in the report for what was guessed " +
                "and why.</div></html>",
        )
        note.border = javax.swing.BorderFactory.createEmptyBorder(4, 4, 8, 4)

        panel.add(note, BorderLayout.NORTH)
        panel.add(JBScrollPane(newTable), BorderLayout.CENTER)
        return panel
    }

    private fun comboEditor(): TableCellEditor {
        val combo = JComboBox(Classification.entries.toTypedArray())
        return DefaultCellEditor(combo)
    }

    /**
     * Prefers the real, complete profile list from the last analysis; otherwise leaves the table
     * empty rather than falling back to a scan.
     *
     * `Configurable.createComponent()` / `reset()` run on the EDT. This used to fall back to a
     * quick content-root walk (`ProfileNameScanner`) so the table had something to show before
     * the user ever ran an analysis — cheap on this project's own fixture, but a real project's
     * content-root walk run synchronously on the EDT is a visible freeze the moment Settings is
     * opened. The empty-state message already tells the user to run one first, which is a fine
     * substitute for a preview that isn't worth blocking the UI thread for.
     */
    private fun loadRows(): List<Row> {
        val classification = ConfigDriftProjectSettings.getInstance(project).manualClassification()
        val profileNames = project.service<ConfigDriftService>().lastReport
            ?.profiles
            ?.map { it.name }
            ?.filter { it != "default" }
            ?: emptyList()

        return profileNames.sorted().map { name ->
            val rowClassification = when (name) {
                in classification.manualComplete -> Classification.COMPLETE
                in classification.manualOverlay -> Classification.OVERLAY
                else -> Classification.AUTO
            }
            Row(name, rowClassification)
        }
    }

    override fun isModified(): Boolean {
        val model = tableModel ?: return false
        val classification = ConfigDriftProjectSettings.getInstance(project).manualClassification()
        return model.rowsSnapshot().any { row ->
            val persisted = when (row.profileName) {
                in classification.manualComplete -> Classification.COMPLETE
                in classification.manualOverlay -> Classification.OVERLAY
                else -> Classification.AUTO
            }
            persisted != row.classification
        }
    }

    override fun apply() {
        val model = tableModel ?: return
        val complete = mutableSetOf<String>()
        val overlay = mutableSetOf<String>()
        for (row in model.rowsSnapshot()) {
            when (row.classification) {
                Classification.COMPLETE -> complete += row.profileName
                Classification.OVERLAY -> overlay += row.profileName
                Classification.AUTO -> Unit
            }
        }
        ConfigDriftProjectSettings.getInstance(project).setManualClassification(complete, overlay)

        // These settings feed the overlay heuristic, which only runs during analysis — so storing
        // them changes nothing the user can see until the analysis runs again. Without this, a
        // profile reclassified here stayed unchanged in the tool window and in the editor
        // highlights until the user happened to invoke Tools | Analyze manually.
        project.service<ConfigDriftService>().requestReanalysis()
    }

    override fun reset() {
        tableModel?.replaceRows(loadRows())
    }

    /**
     * Settings pages are long-lived once opened; holding the table and its model after the dialog
     * closes keeps a chunk of Swing hierarchy (and through it this project) reachable.
     */
    override fun disposeUIResources() {
        table = null
        tableModel = null
    }
}
