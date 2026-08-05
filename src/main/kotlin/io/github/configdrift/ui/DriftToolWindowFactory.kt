package io.github.configdrift.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import io.github.configdrift.ConfigDriftService
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Finding
import io.github.configdrift.model.OverlayProfileExcluded
import io.github.configdrift.model.Severity
import io.github.configdrift.report.ReportRenderer
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

class DriftToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DriftToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Findings table, key matrix, a Suppressed tab, and report export.
 *
 * Export copies to the clipboard rather than writing a file: the report can contain the full key
 * inventory of every environment, so where it lands should be the user's explicit choice.
 */
class DriftToolWindowPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val findingsModel = FindingsTableModel()
    private val findingsTable = JBTable(findingsModel)

    private val suppressedModel = FindingsTableModel()
    private val suppressedTable = JBTable(suppressedModel)

    private val matrixModel = MatrixTableModel()
    private val matrixTable = JBTable(matrixModel)

    // Reads the rows the sorter is currently letting through, so the summary always describes
    // exactly what the user can see.
    private val severityCounts = SeverityCountsPanel {
        (0 until findingsTable.rowCount).mapNotNull {
            findingsModel.findingAt(findingsTable.convertRowIndexToModel(it))
        }
    }

    // Declared without an initializer but assigned exactly once, in init{} below — Kotlin's
    // definite-assignment analysis accepts that as a val, and onReport (also assigned here, but
    // not invoked until the listener fires later) can rely on it always being set by then.
    private val tabs: JBTabbedPane

    private val onReport: (DriftReport) -> Unit = { report ->
        findingsModel.setReport(report)
        severityCounts.refresh(report)
        suppressedModel.setFindings(report.suppressedFindings)
        matrixModel.setReport(report)
        // Re-applied per report: the matrix rebuilds its columns whenever the profile set changes,
        // which discards any width set earlier.
        tuneMatrixColumnWidths()
        tabs.setTitleAt(
            SUPPRESSED_TAB_INDEX,
            if (report.suppressedFindings.isEmpty()) "Suppressed" else "Suppressed (${report.suppressedFindings.size})",
        )
    }

    init {
        configureFindingsTable(
            findingsTable,
            findingsModel,
            // The main screen's first impression: an empty table with no explanation reads as
            // broken, and nothing else tells a new user that analysis is theirs to start.
            emptyText = "No analysis yet — run Tools | Analyze Spring Config Drift, or press Refresh above.",
            actionLabel = "Suppress",
            disabledReason = { finding ->
                "Secret exposures cannot be suppressed — externalize the value instead"
                    .takeUnless { finding.suppressible }
            },
        ) { finding ->
            project.service<ConfigDriftService>().suppress(finding)
        }

        configureFindingsTable(
            suppressedTable,
            suppressedModel,
            emptyText = "Nothing suppressed.",
            actionLabel = "Un-suppress",
        ) { finding ->
            project.service<ConfigDriftService>().unsuppress(finding)
        }

        matrixTable.autoCreateRowSorter = true
        matrixTable.autoResizeMode = JBTable.AUTO_RESIZE_OFF

        val filterRow = buildFilterRow(findingsTable)
        val findingsHeader = JPanel(BorderLayout()).apply {
            add(severityCounts, BorderLayout.NORTH)
            add(filterRow, BorderLayout.SOUTH)
        }
        // JPanel rather than a raw java.awt.Container: Container paints no background of its own,
        // so under some themes the gaps around the table show through as the wrong colour.
        val findingsPanel = JPanel(BorderLayout()).apply {
            add(findingsHeader, BorderLayout.NORTH)
            add(JBScrollPane(findingsTable), BorderLayout.CENTER)
        }

        val suppressedPanel = JPanel(BorderLayout()).apply {
            add(
                JBLabel(
                    "Select a suppressed finding and press Delete, or right-click it, to bring " +
                        "it back. Suppressions are shared with the team via .idea/configDrift.xml.",
                ).apply { border = javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4) },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(suppressedTable), BorderLayout.CENTER)
        }

        val matrixHeader = JPanel(BorderLayout()).apply {
            add(
                JBLabel(MatrixTableModel.LEGEND)
                    .apply { border = javax.swing.BorderFactory.createEmptyBorder(4, 4, 2, 4) },
                BorderLayout.NORTH,
            )
            add(buildMatrixFilterRow(), BorderLayout.SOUTH)
        }
        val matrixPanel = JPanel(BorderLayout()).apply {
            add(matrixHeader, BorderLayout.NORTH)
            add(JBScrollPane(matrixTable), BorderLayout.CENTER)
        }

        tabs = JBTabbedPane().apply {
            addTab("Findings", findingsPanel)
            addTab("Suppressed", suppressedPanel)
            addTab("Key Matrix", matrixPanel)
        }
        setContent(tabs)
        toolbar = createToolbar()

        project.service<ConfigDriftService>().addListener(onReport)
    }

    override fun dispose() {
        project.service<ConfigDriftService>().removeListener(onReport)
    }

    private fun createToolbar(): javax.swing.JComponent {
        val service = project.service<ConfigDriftService>()
        val group = DefaultActionGroup().apply {
            add(RerunAction())
            addSeparator()
            service.renderers.forEach { add(CopyReportAction(it)) }
        }
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("ConfigDrift.Toolbar", group, true)
        actionToolbar.targetComponent = findingsTable
        return actionToolbar.component
    }

    /**
     * Applies everything the Findings and Suppressed tables share.
     *
     * They differ only in their empty-state text and in what their row action does, so keeping
     * two hand-written copies of the setup let them drift apart in ways nobody noticed — one
     * gained a filter row and a call-to-action empty state while the other kept a bare message.
     * Anything added here now lands on both.
     */
    private fun configureFindingsTable(
        table: JBTable,
        model: FindingsTableModel,
        emptyText: String,
        actionLabel: String,
        disabledReason: (Finding) -> String? = { null },
        onAction: (Finding) -> Unit,
    ) {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoCreateRowSorter = true
        table.setShowGrid(false)
        table.getColumn("Severity").cellRenderer = SeverityCellRenderer()
        table.emptyText.text = emptyText
        tuneFindingsColumnWidths(table)
        installCellTooltip(table, model)

        val navigate = NavigateAction(project, table, model)
        navigate.registerCustomShortcutSet(CommonShortcuts.ENTER, table, this)
        table.addMouseListener(NavigateOnDoubleClick(navigate))

        // Delete, because on both tabs the operation means "take this row off the list I'm
        // looking at". Scoped to the table and tied to this panel's lifetime so the binding goes
        // away with the tool window.
        val rowAction = SuppressionAction(table, model, actionLabel, disabledReason, onAction)
        rowAction.registerCustomShortcutSet(CommonShortcuts.getDelete(), table, this)
        table.addMouseListener(SuppressionPopup(table, rowAction))
    }

    /**
     * A key column that's readable at a glance, and a Detail column wide enough that most
     * messages don't need the tooltip at all — the default even split left Detail, the column
     * that actually needs the room, no wider than Location.
     */
    private fun tuneFindingsColumnWidths(table: JTable) {
        table.columnModel.apply {
            getColumn(0).preferredWidth = 90 // Severity
            getColumn(1).preferredWidth = 160 // Key
            getColumn(2).preferredWidth = 480 // Detail
            getColumn(3).preferredWidth = 220 // Location
        }
    }

    /**
     * The matrix runs with AUTO_RESIZE_OFF so that many profiles scroll horizontally rather than
     * being squeezed. That also means Swing's 75px default applies to every column, including the
     * key — far too narrow for names like `spring.jpa.hibernate.ddl-auto`. Profile columns hold a
     * single character and can stay small.
     */
    private fun tuneMatrixColumnWidths() {
        val columns = matrixTable.columnModel
        if (columns.columnCount == 0) return
        columns.getColumn(0).preferredWidth = 380
        for (index in 1 until columns.columnCount) {
            columns.getColumn(index).preferredWidth = 90
        }
    }

    /**
     * Shows a row's full message on hover, for whichever cell in it — not just the Detail column
     * — since a long key or path can be just as truncated by a narrow tool window.
     */
    private fun installCellTooltip(table: JTable, model: FindingsTableModel) {
        table.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    val viewRow = table.rowAtPoint(event.point).takeIf { it >= 0 }
                    table.toolTipText = viewRow
                        ?.let { model.findingAt(table.convertRowIndexToModel(it)) }
                        ?.message
                }
            },
        )
    }

    /**
     * A free-text search plus a severity dropdown, applied through the table's own row sorter
     * rather than a second copy of the data — [JBTable.autoCreateRowSorter] already installed a
     * [TableRowSorter], so filtering is just replacing its [RowFilter] on every keystroke.
     */
    private fun buildFilterRow(table: JTable): JPanel {
        val sorter = table.rowSorter as TableRowSorter<TableModel>
        var query = ""
        var severity: Severity? = null

        fun applyFilter() {
            val currentQuery = query
            val currentSeverity = severity
            sorter.rowFilter = object : RowFilter<TableModel, Int>() {
                override fun include(entry: Entry<out TableModel, out Int>): Boolean {
                    if (currentSeverity != null && entry.getValue(0) != currentSeverity) return false
                    if (currentQuery.isBlank()) return true
                    return (1..3).any {
                        entry.getStringValue(it).contains(currentQuery, ignoreCase = true)
                    }
                }
            }
            severityCounts.refreshCounts()
        }

        val search = SearchTextField().apply {
            toolTipText = "Filter by key, message, or location"
            addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        query = text
                        applyFilter()
                    }
                },
            )
        }

        val severityFilter = JComboBox(arrayOf("All", "Error", "Warning", "Info")).apply {
            toolTipText = "Filter by severity"
            addActionListener {
                severity = (selectedItem as String).takeIf { it != "All" }
                    ?.let { Severity.valueOf(it.uppercase()) }
                applyFilter()
            }
        }

        return JPanel(BorderLayout()).apply {
            add(search, BorderLayout.CENTER)
            add(severityFilter, BorderLayout.EAST)
            border = javax.swing.BorderFactory.createEmptyBorder(2, 4, 4, 4)
        }
    }

    /**
     * Search plus a "gaps only" toggle for the matrix.
     *
     * The matrix needs this more than the findings table does: findings only lists things that are
     * wrong, whereas the matrix lists *every* key in the project, so on a real codebase it is
     * hundreds of rows of mostly-fine configuration. Narrowing to rows that have a gap in some
     * profile turns it from an inventory into something answerable.
     */
    private fun buildMatrixFilterRow(): JPanel {
        val sorter = matrixTable.rowSorter as TableRowSorter<TableModel>
        var query = ""
        var gapsOnly = false

        fun applyFilter() {
            val currentQuery = query
            val currentGapsOnly = gapsOnly
            sorter.rowFilter = object : RowFilter<TableModel, Int>() {
                override fun include(entry: Entry<out TableModel, out Int>): Boolean {
                    if (currentQuery.isNotBlank() &&
                        !entry.getStringValue(0).contains(currentQuery, ignoreCase = true)
                    ) {
                        return false
                    }
                    if (!currentGapsOnly) return true
                    // valueCount rather than a captured column count: the profile set — and so the
                    // column count — changes whenever a new report arrives.
                    return (1 until entry.valueCount).any {
                        entry.getStringValue(it) == MatrixTableModel.MISSING
                    }
                }
            }
        }

        val search = SearchTextField().apply {
            toolTipText = "Filter by key"
            addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        query = text
                        applyFilter()
                    }
                },
            )
        }

        val scope = JComboBox(arrayOf("All keys", "Only keys missing somewhere")).apply {
            toolTipText = "Show every key, or only those absent from at least one profile"
            addActionListener {
                gapsOnly = selectedIndex == 1
                applyFilter()
            }
        }

        return JPanel(BorderLayout()).apply {
            add(search, BorderLayout.CENTER)
            add(scope, BorderLayout.EAST)
            border = javax.swing.BorderFactory.createEmptyBorder(2, 4, 4, 4)
        }
    }

    private companion object {
        /** Must match the order tabs are added to [tabs] in `init`. */
        const val SUPPRESSED_TAB_INDEX = 1
    }

    private inner class RerunAction :
        AnAction("Rerun Analysis", null, AllIcons.Actions.Refresh) {

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        // Shares the launcher function rather than delegating to the Tools-menu action: invoking
        // another AnAction's actionPerformed is an @ApiStatus.OverrideOnly violation.
        override fun actionPerformed(e: AnActionEvent) = launchConfigDriftAnalysis(project)
    }

    private inner class CopyReportAction(private val renderer: ReportRenderer) :
        AnAction(
            "Copy ${renderer.id.uppercase()} Report",
            "Copy the full report to the clipboard as ${renderer.id}",
            // Without an icon these render as text buttons beside RerunAction's icon, making the
            // toolbar look half-finished — but a shared Copy icon on both would be worse, since
            // an icon-only toolbar would then show two indistinguishable buttons.
            when (renderer.fileExtension) {
                "json" -> AllIcons.FileTypes.Json
                else -> AllIcons.FileTypes.Text
            },
        ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = project.service<ConfigDriftService>().lastReport != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val report = project.service<ConfigDriftService>().lastReport ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(renderer.render(report)))
        }
    }
}

/**
 * The counts a Markdown report shows in its summary table, made visible in the tool window too:
 * without this, telling "how bad is it" apart from "what exactly is wrong" required opening the
 * tab and counting rows.
 *
 * Also carries the analysis timestamp. It matters more here than it would for a synchronous
 * report: automatic re-analysis is debounced by a couple of seconds after a save, so what's on
 * screen briefly lags the file on disk, and a visible "as of HH:mm:ss" is what tells the user
 * whether that lag has caught up rather than leaving them to guess.
 */
private class SeverityCountsPanel(
    /** The findings currently passing the table's filter; all of them when nothing is filtered. */
    private val visibleFindings: () -> List<Finding>,
) : JPanel(FlowLayout(FlowLayout.LEFT, 16, 2)) {

    private val errorLabel = JBLabel(AllIcons.General.Error)
    private val warningLabel = JBLabel(AllIcons.General.Warning)
    private val infoLabel = JBLabel(AllIcons.General.Information)
    private val timestampLabel = JBLabel()
    private val filteredLabel = JBLabel()

    private var lastFindings: List<Finding> = emptyList()

    init {
        border = javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 4)
        add(errorLabel)
        add(warningLabel)
        add(infoLabel)
        add(filteredLabel)
        add(timestampLabel)
        // ConfigDriftService.addListener only replays the last report if one already exists, so
        // before the first analysis this panel would otherwise show bare icons with no text at
        // all — set the zero state explicitly rather than leaving it to the first real update.
        refresh(null)
    }

    // Named refresh rather than update: AWT's Component.update(Graphics) is also visible here,
    // and passing a null DriftReport is ambiguous between the two overloads.
    fun refresh(report: DriftReport?) {
        lastFindings = report?.findings.orEmpty()
        timestampLabel.text = report?.generatedAtEpochMillis?.let {
            "· as of ${TIME_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}"
        } ?: "· not yet analyzed"
        refreshCounts()
    }

    /**
     * Recounts against the rows actually on screen.
     *
     * Counting the whole report instead would contradict the table the moment a filter is
     * applied — the header claiming "8 Error" above three visible rows reads as a bug, not as a
     * filtered view.
     */
    fun refreshCounts() {
        val visible = visibleFindings()
        val counts = visible.groupingBy { it.severity }.eachCount()
        errorLabel.text = "${counts[Severity.ERROR] ?: 0} Error"
        warningLabel.text = "${counts[Severity.WARNING] ?: 0} Warning"
        infoLabel.text = "${counts[Severity.INFO] ?: 0} Info"

        val hiddenCount = lastFindings.size - visible.size
        filteredLabel.text = if (hiddenCount > 0) "· $hiddenCount hidden by filter" else ""
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/**
 * Draws the Severity column as an icon rather than the bare enum name — the convention IntelliJ's
 * own Problems view and inspection results use, so it reads consistently with the rest of the IDE
 * and needs no color tuning of its own for light/dark themes.
 */
private class SeverityCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val severity = value as? Severity
        icon = when (severity) {
            Severity.ERROR -> AllIcons.General.Error
            Severity.WARNING -> AllIcons.General.Warning
            Severity.INFO -> AllIcons.General.Information
            null -> null
        }
        text = severity?.name ?: ""
        iconTextGap = 4
        return label
    }
}

/**
 * Feature 9: jump to the selected finding, from a double-click or Enter.
 *
 * Not every finding points at a file. [io.github.configdrift.model.OverlayProfileExcluded] is
 * about a profile-level decision, and its own message tells the user to change it in settings —
 * so that is where it navigates, rather than doing nothing because there is no source location.
 * Findings that genuinely have nowhere to go stay disabled, which the Location column's "—" and
 * the greyed-out action explain, instead of the click silently no-op'ing.
 */
private class NavigateAction(
    private val project: Project,
    private val table: JTable,
    private val model: FindingsTableModel,
) : AnAction("Jump to Source") {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selectedFinding()?.let { it.location != null || it.opensSettings } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val finding = selectedFinding() ?: return
        val location = finding.location
        when {
            location != null -> SourceNavigator.navigate(project, location)
            finding.opensSettings ->
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, ConfigDriftConfigurable::class.java)
        }
    }

    private fun selectedFinding(): Finding? =
        table.selectedRow.takeIf { it >= 0 }
            ?.let { model.findingAt(table.convertRowIndexToModel(it)) }

    private val Finding.opensSettings: Boolean
        get() = this is OverlayProfileExcluded
}

/** Runs [action] on double-click, so the mouse and Enter share one implementation. */
private class NavigateOnDoubleClick(private val action: AnAction) : MouseAdapter() {
    override fun mouseClicked(event: MouseEvent) {
        if (event.clickCount != 2) return
        ActionManager.getInstance().tryToExecute(
            action,
            event,
            event.component,
            null,
            /* now = */ true,
        )
    }
}

/**
 * "Suppress" / "Un-suppress" for whichever row is selected.
 *
 * A single action drives both the right-click menu and the Delete shortcut, so the two can never
 * disagree about when the operation is allowed — reading the selection in [update] rather than
 * capturing a finding at popup time is what makes it usable from the keyboard at all.
 */
private class SuppressionAction(
    private val table: JTable,
    private val model: FindingsTableModel,
    private val label: String,
    /** Returns why the action is unavailable for this finding, or null when it is available. */
    private val disabledReason: (Finding) -> String? = { null },
    private val onAction: (Finding) -> Unit,
) : AnAction(label) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val finding = selectedFinding()
        val reason = finding?.let(disabledReason)
        e.presentation.isEnabled = finding != null && reason == null
        // An unavailable action keeps its place with the reason as its text rather than vanishing:
        // a right-click that opens an empty menu reads as a broken plugin, not a deliberate
        // refusal.
        e.presentation.text = reason ?: label
    }

    override fun actionPerformed(e: AnActionEvent) {
        selectedFinding()?.let(onAction)
    }

    private fun selectedFinding(): Finding? =
        table.selectedRow.takeIf { it >= 0 }
            ?.let { model.findingAt(table.convertRowIndexToModel(it)) }
}

/**
 * Opens [action] as a context menu, selecting the row under the cursor first — otherwise the menu
 * would act on a stale selection left over from an earlier left-click.
 *
 * Built through the platform action system rather than a raw JPopupMenu/JMenuItem: a plain Swing
 * popup renders as an empty, unthemed box under the IDE's Look and Feel.
 */
private class SuppressionPopup(
    private val table: JTable,
    private val action: AnAction,
) : MouseAdapter() {

    override fun mousePressed(event: MouseEvent) = maybeShow(event)
    override fun mouseReleased(event: MouseEvent) = maybeShow(event)

    private fun maybeShow(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val viewRow = table.rowAtPoint(event.point).takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(viewRow, viewRow)

        ActionManager.getInstance()
            .createActionPopupMenu("ConfigDrift.SuppressionPopup", DefaultActionGroup(action))
            .component
            .show(table, event.x, event.y)
    }
}
