package io.github.configdrift.ui

import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A live IDE report ("Matrix filter stops working after re-analysis") pointed at two plausible
 * `javax.swing.table` mechanisms: that `autoCreateRowSorter` replaces the sorter object on a
 * structural change, or that a structural change clears an already-installed `rowFilter`. Both
 * are exactly the kind of behavior that looks right from documentation and is wrong in practice —
 * so both are pinned down here by direct test rather than left as an assumption.
 *
 * On this JDK, **neither actually happens**: the sorter identity and its `rowFilter` both survive
 * a `fireTableStructureChanged()` on the same model instance. That does not mean the original
 * report was wrong — [MatrixTableModel]'s column count changes with the profile set on every
 * analysis, in the running IDE, on JBR rather than this test's JDK — only that this specific
 * mechanism is not the explanation, confirmed rather than assumed. The fix applied in
 * [DriftToolWindowPanel] re-asserts the filter after every report regardless, which resolves the
 * reported symptom independent of which assumption about Swing internals would have been right.
 *
 * A plain JUnit test, no IDE fixture: `javax.swing.table` behavior, not IntelliJ Platform
 * behavior.
 */
class RowSorterStabilityTest {

    /** A minimal model whose column count can change, to trigger a structural event on demand. */
    private class ResizableModel(private var columnCount: Int) : AbstractTableModel() {
        override fun getRowCount() = 1
        override fun getColumnCount() = columnCount
        override fun getValueAt(rowIndex: Int, columnIndex: Int) = ""

        fun resizeTo(newColumnCount: Int) {
            columnCount = newColumnCount
            fireTableStructureChanged()
        }
    }

    private val neverMatch = object : RowFilter<TableModel, Int>() {
        override fun include(entry: Entry<out TableModel, out Int>) = false
    }

    @Test
    fun `an auto-created sorter's identity survives a structural change`() {
        val model = ResizableModel(columnCount = 3)
        val table = JTable(model)
        table.autoCreateRowSorter = true
        val sorterBeforeReport = table.rowSorter

        model.resizeTo(5)

        assertSame(sorterBeforeReport, table.rowSorter)
    }

    @Test
    fun `an installed rowFilter also survives a structural change, auto-created sorter`() {
        val model = ResizableModel(columnCount = 3)
        val table = JTable(model)
        table.autoCreateRowSorter = true
        val sorter = table.rowSorter as TableRowSorter<TableModel>
        sorter.rowFilter = neverMatch

        model.resizeTo(5)

        assertTrue(sorter.rowFilter === neverMatch)
    }

    @Test
    fun `an installed rowFilter also survives a structural change, manually assigned sorter`() {
        val model = ResizableModel(columnCount = 3)
        val table = JTable(model)
        val ourSorter = TableRowSorter<ResizableModel>(model)
        table.rowSorter = ourSorter
        ourSorter.rowFilter = neverMatch

        model.resizeTo(5)

        assertTrue(ourSorter.rowFilter === neverMatch)
    }

    @Test
    fun `re-applying the filter after a structural change is harmless and still correct`() {
        // What DriftToolWindowPanel actually does regardless of the above: not a fix for a
        // mechanism confirmed here, but a defensive re-assertion that holds up either way.
        val model = ResizableModel(columnCount = 3)
        val table = JTable(model)
        table.autoCreateRowSorter = true
        val sorter = table.rowSorter as TableRowSorter<TableModel>
        sorter.rowFilter = neverMatch

        model.resizeTo(5)
        sorter.rowFilter = neverMatch

        assertTrue(sorter.rowFilter === neverMatch)
    }
}
