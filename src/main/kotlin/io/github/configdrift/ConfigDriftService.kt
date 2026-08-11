package io.github.configdrift

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.configdrift.discovery.ConfigFileDiscovery
import io.github.configdrift.engine.AnalysisContext
import io.github.configdrift.engine.DriftAnalysisEngine
import io.github.configdrift.engine.FindingFingerprint
import io.github.configdrift.engine.MonotonicSequenceGate
import io.github.configdrift.model.ConfigSnapshot
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Finding
import io.github.configdrift.model.ProfileSnapshot
import io.github.configdrift.parser.ConfigFileParser
import io.github.configdrift.parser.DockerComposeConfigParser
import io.github.configdrift.parser.DotenvConfigParser
import io.github.configdrift.parser.ParseSupport
import io.github.configdrift.parser.ParsedDocument
import io.github.configdrift.parser.PropertiesConfigParser
import io.github.configdrift.parser.YamlConfigParser
import io.github.configdrift.report.JsonReportRenderer
import io.github.configdrift.report.MarkdownReportRenderer
import io.github.configdrift.report.ReportRenderer
import io.github.configdrift.secrets.SecretDetector
import io.github.configdrift.settings.ConfigDriftProjectSettings
import io.github.configdrift.spi.BindingContractProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Wires discovery → parsing → analysis, and holds the most recent report so the tool window can
 * repopulate itself after being closed and reopened.
 */
@Service(Service.Level.PROJECT)
class ConfigDriftService(private val project: Project) {

    private val parsers: List<ConfigFileParser> =
        listOf(YamlConfigParser(), PropertiesConfigParser(), DotenvConfigParser(), DockerComposeConfigParser())

    private val listeners = CopyOnWriteArrayList<(DriftReport) -> Unit>()

    @Volatile
    var lastReport: DriftReport? = null
        private set

    val renderers: List<ReportRenderer> =
        listOf(MarkdownReportRenderer(), JsonReportRenderer())

    fun addListener(listener: (DriftReport) -> Unit) {
        listeners += listener
        lastReport?.let(listener)
    }

    fun removeListener(listener: (DriftReport) -> Unit) {
        listeners -= listener
    }

    /**
     * Call from a background thread. The whole run sits inside one read action: parsing touches
     * PSI, and the metadata contract provider reads VFS, so both need it.
     *
     * Returns every finding, including ones the user has suppressed. Filtering happens in
     * [publish] instead, on the EDT — see there for why doing it here was a race.
     */
    fun analyze(): DriftReport = ReadAction.nonBlocking<DriftReport> {
        val support = ParseSupport(project, SecretDetector())
        val psiManager = PsiManager.getInstance(project)

        val documents: List<ParsedDocument> = ConfigFileDiscovery()
            .discoverConfigFiles(project)
            .flatMap { file ->
                val psiFile = psiManager.findFile(file) ?: return@flatMap emptyList()
                val parser = parsers.firstOrNull { it.supports(file) } ?: return@flatMap emptyList()
                parser.parse(support, psiFile)
            }

        val snapshot = ConfigSnapshot(
            documents.groupBy { it.profile }
                .map { (profile, docs) ->
                    ProfileSnapshot(profile, docs.flatMap { it.entries })
                },
        )

        val context = AnalysisContext(
            project = project,
            snapshot = snapshot,
            secretHits = documents.flatMap { it.secretHits },
            contractProviders = BindingContractProvider.EP_NAME.extensionList,
        )

        DriftAnalysisEngine().analyze(project.name, context)
    }.executeSynchronously()

    /**
     * Publish on the EDT; the tool window updates its table from the listener callback.
     *
     * Suppressions are applied here rather than at the end of [analyze] so that the read of the
     * suppression set and every write to it happen on the same thread. Filtering inside the
     * background analysis left a window between "read the suppressed ids" and "publish": a
     * finding suppressed by the user during that window was still present in the report about to
     * be published, so dismissing a finding while a save-triggered re-analysis was in flight made
     * it reappear a moment later. Both suppression paths ([suppress] / [unsuppress], invoked from
     * the quick fix and the tool window) already run on the EDT, so moving the read here
     * serialises them against each other.
     */
    fun publish(report: DriftReport) {
        val filtered = applySuppressions(report)
        lastReport = filtered
        listeners.forEach { it(filtered) }
    }

    /**
     * Schedules a re-analysis, collapsing bursts into a single run.
     *
     * Debouncing is not an optimisation here, it is a correctness guard on cost: the IDE flushes
     * documents to disk on focus changes, not just on an explicit save, so switching tabs a few
     * times can fire this several times a second. Each pending request cancels the previous one,
     * so a burst of saves costs one whole-project analysis rather than one per event.
     *
     * That cancellation only reaches a request that hasn't *started* yet. Once the debounce
     * timer fires and hands off to [analyzeInBackgroundAndPublish], nothing here can stop it —
     * and if a slow manual "Tools | Analyze" is still running when that happens, both are now in
     * flight with no relationship to each other. [analyzeInBackgroundAndPublish]'s sequence number
     * is what actually protects against that, not this method.
     */
    fun requestReanalysis() {
        synchronized(scheduleLock) {
            pendingReanalysis?.cancel(false)
            pendingReanalysis = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                { analyzeInBackgroundAndPublish() },
                REANALYSIS_DEBOUNCE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * Runs the analysis as a background task and publishes the result — the one path both the
     * manual "Tools | Analyze" action and automatic re-analysis go through.
     *
     * A shared path is what makes the sequence number below meaningful: when the two used to
     * queue their own separate `Task.Backgroundable`s, whichever happened to *finish* last won,
     * regardless of which one *started* last — a slow manual analysis begun before an edit could
     * finish after the auto re-analysis that edit triggered, and overwrite a newer result with a
     * stale one. The sequence number is assigned when a run is requested, not when it completes,
     * so a run that reflects older file state can never clobber one that reflects newer state,
     * whichever thread happens to reach [publish] first.
     *
     * Only the debounce *wait* in [requestReanalysis] runs on the shared application scheduler —
     * appropriate for it, since a delayed hand-off is exactly what that pool is for. The analysis
     * itself (content-root traversal, PSI parsing) runs as a proper `Task.Backgroundable` instead:
     * the scheduler pool is explicitly documented as unsuited to long-running work (JetBrains
     * points to `getAppExecutorService()` or the Progress API for that) and is shared across the
     * whole platform, so blocking one of its threads for a whole-project walk would risk delaying
     * unrelated scheduled callbacks elsewhere in the IDE.
     *
     * `Task.Backgroundable.queue()` requires the EDT, hence the `invokeLater` hop — needed
     * unconditionally since callers include both EDT (the menu action) and the scheduler's
     * background thread (automatic re-analysis).
     */
    fun analyzeInBackgroundAndPublish(onSuccess: () -> Unit = {}) {
        val sequence = sequenceGate.issue()
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            object : Task.Backgroundable(project, "Analyzing Spring config drift", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val report = try {
                        analyze()
                    } catch (e: ProcessCanceledException) {
                        // A cancelled read action just means the next request will pick it up.
                        return
                    } catch (e: Throwable) {
                        // Uncaught exceptions from a queued Task are reported to the IDE's own
                        // error log, unlike the raw ScheduledExecutorService this once ran on —
                        // this catch is now a belt-and-braces log message, not the only signal.
                        thisLogger().warn("Config Drift analysis failed", e)
                        return
                    }

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        // Discards this result if a run requested later has already published —
                        // that one reflects newer file state regardless of which finished first.
                        // onSuccess still runs even when this run loses the race: the manual
                        // "Tools | Analyze" caller's whole point is to open the tool window once
                        // an analysis it asked for has finished, and that should happen regardless
                        // of whether the visible results end up being this run's or a newer
                        // concurrent one's — the tool window always renders lastReport, which is
                        // guaranteed to be at least as fresh either way. Gating onSuccess on
                        // winning the publish race used to mean a save-triggered re-analysis that
                        // happened to finish around the same time could silently swallow the
                        // manual action's own tool-window activation.
                        if (sequenceGate.tryPublish(sequence)) {
                            publish(report)
                            // Inspections read lastReport rather than computing anything, so they
                            // only pick up a new result once the daemon is asked to run again.
                            // Project-wide, because one analysis can change findings in every
                            // config file at once.
                            DaemonCodeAnalyzer.getInstance(project).restart("Config Drift analysis finished")
                        }
                        onSuccess()
                    }
                }
            }.queue()
        }
    }

    private val sequenceGate = MonotonicSequenceGate()

    private val scheduleLock = Any()

    @Volatile
    private var pendingReanalysis: ScheduledFuture<*>? = null

    /**
     * Dismisses a finding without re-running the (PSI-based, comparatively expensive) analysis:
     * re-splits the already-computed findings using the updated suppression set.
     */
    fun suppress(finding: Finding) {
        // Enforced here rather than only in the UI: this is the single point every suppression
        // path goes through, so a menu item or quick fix that forgets the check cannot bypass it.
        if (!finding.suppressible) return
        updateSuppressions { it += FindingFingerprint.of(finding) }
    }

    fun unsuppress(finding: Finding) {
        updateSuppressions { it -= FindingFingerprint.of(finding) }
    }

    private fun updateSuppressions(mutate: (MutableSet<String>) -> Unit) {
        ConfigDriftProjectSettings.getInstance(project).mutateSuppressedFindingIds(mutate)
        val current = lastReport ?: return
        // Hand publish() the *unfiltered* report — it re-partitions from scratch, which is what
        // makes un-suppressing able to bring a finding back rather than only ever hiding more.
        publish(
            current.copy(
                findings = current.findings + current.suppressedFindings,
                suppressedFindings = emptyList(),
            ),
        )
        // The tool window updates itself through the listener `publish` already notifies, but the
        // editor's inline highlights are the inspection's own business — nothing repaints them
        // until the daemon is explicitly told to. Callers (the quick fix, the tool window's
        // right-click menu) both run on the EDT already, so this can run synchronously.
        DaemonCodeAnalyzer.getInstance(project).restart("Config Drift suppression changed")
    }

    private companion object {
        /**
         * Long enough that a tab switch storm collapses into one run, short enough that the
         * highlight feels like a consequence of the edit rather than a background chore.
         */
        const val REANALYSIS_DEBOUNCE_MILLIS = 2_500L
    }

    private fun applySuppressions(report: DriftReport): DriftReport {
        val suppressedIds = ConfigDriftProjectSettings.getInstance(project).suppressedFindingIds()
        if (suppressedIds.isEmpty()) return report

        // The suppressible check is repeated on the read path so a settings file written before
        // secret exposures became non-suppressible — or hand-edited — cannot hide one.
        val (hidden, visible) = report.findings.partition {
            it.suppressible && FindingFingerprint.of(it) in suppressedIds
        }
        return report.copy(findings = visible, suppressedFindings = hidden)
    }
}
