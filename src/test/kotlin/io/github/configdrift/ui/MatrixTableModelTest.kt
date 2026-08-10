package io.github.configdrift.ui

import io.github.configdrift.model.CellState
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import kotlin.test.Test
import kotlin.test.assertEquals

class MatrixTableModelTest {

    private fun reportWith(profiles: List<String>, keys: List<String>): DriftReport {
        val profileIds = profiles.map { ProfileId(it) }
        return DriftReport(
            projectName = "test",
            generatedAtEpochMillis = 0,
            profiles = profileIds,
            matrix = keys.associate { key ->
                NormalizedKey(key) to profileIds.associateWith { CellState.SET }
            },
            findings = emptyList(),
        )
    }

    @Test
    fun `getColumnName degrades to blank instead of throwing for a stale column index`() {
        val model = MatrixTableModel()
        model.setReport(reportWith(profiles = listOf("dev", "prod", "stage"), keys = listOf("a.b")))

        // JTable's header repaint runs off the Swing event queue and is not guaranteed to happen
        // before fireTableStructureChanged() takes effect — a column index valid a moment ago can
        // reach getColumnName() after the profile set has already shrunk.
        model.setReport(reportWith(profiles = listOf("dev"), keys = listOf("a.b")))

        assertEquals("", model.getColumnName(3))
    }

    @Test
    fun `getColumnName still resolves a live column`() {
        val model = MatrixTableModel()
        model.setReport(reportWith(profiles = listOf("dev", "prod"), keys = listOf("a.b")))

        assertEquals("Key", model.getColumnName(0))
        assertEquals("dev", model.getColumnName(1))
        assertEquals("prod", model.getColumnName(2))
    }
}
