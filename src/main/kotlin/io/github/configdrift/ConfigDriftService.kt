package io.github.configdrift

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.configdrift.discovery.ConfigFileDiscovery
import io.github.configdrift.engine.AnalysisContext
import io.github.configdrift.engine.DriftAnalysisEngine
import io.github.configdrift.engine.FindingFingerprint
import io.github.configdrift.model.ConfigSnapshot
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Finding
import io.github.configdrift.model.ProfileSnapshot
import io.github.configdrift.parser.ConfigFileParser
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
        listOf(YamlConfigParser(), PropertiesConfigParser())

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

        applySuppressions(DriftAnalysisEngine().analyze(project.name, context))
    }.executeSynchronously()

    /** Publish on the EDT; the tool window updates its table from the listener callback. */
    fun publish(report: DriftReport) {
        lastReport = report
        listeners.forEach { it(report) }
    }

    /**
     * Schedules a re-analysis, collapsing bursts into a single run.
     *
     * Debouncing is not an optimisation here, it is a correctness guard on cost: the IDE flushes
     * documents to disk on focus changes, not just on an explicit save, so switching tabs a few
     * times can fire this several times a second. Each pending request cancels the previous one,
     * so a burst of saves costs one whole-project analysis rather than one per event.
     */
    fun requestReanalysis() {
        synchronized(scheduleLock) {
            pendingReanalysis?.cancel(false)
            pendingReanalysis = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                ::runScheduledReanalysis,
                REANALYSIS_DEBOUNCE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun runScheduledReanalysis() {
        if (project.isDisposed) return
        val report = try {
            analyze()
        } catch (e: ProcessCanceledException) {
            // A cancelled read action just means the next request will pick it up.
            return
        } catch (e: Throwable) {
            // A ScheduledExecutorService swallows an uncaught exception from a submitted task
            // entirely — no log, no stack trace, nothing — unless the returned Future is polled,
            // which nothing here does. Without this catch, a bug in the background re-analysis
            // path would fail silently forever instead of merely once.
            thisLogger().warn("Automatic Config Drift re-analysis failed", e)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            publish(report)
            // Inspections read lastReport rather than computing anything, so they only pick up a
            // new result once the daemon is asked to run again. Project-wide, because a single
            // analysis can change findings in every config file at once.
            DaemonCodeAnalyzer.getInstance(project).restart("Config Drift analysis finished")
        }
    }

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
        mutate(ConfigDriftProjectSettings.getInstance(project).state.suppressedFindingIds)
        val current = lastReport ?: return
        val everything = current.copy(
            findings = current.findings + current.suppressedFindings,
            suppressedFindings = emptyList(),
        )
        publish(applySuppressions(everything))
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
        val suppressedIds = ConfigDriftProjectSettings.getInstance(project).state.suppressedFindingIds
        if (suppressedIds.isEmpty()) return report

        // The suppressible check is repeated on the read path so a settings file written before
        // secret exposures became non-suppressible — or hand-edited — cannot hide one.
        val (hidden, visible) = report.findings.partition {
            it.suppressible && FindingFingerprint.of(it) in suppressedIds
        }
        return report.copy(findings = visible, suppressedFindings = hidden)
    }
}
