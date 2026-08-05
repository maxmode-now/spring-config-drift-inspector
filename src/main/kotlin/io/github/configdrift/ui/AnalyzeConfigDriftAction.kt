package io.github.configdrift.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.github.configdrift.ConfigDriftService

/** Entry point: Tools | Analyze Spring Config Drift. */
class AnalyzeConfigDriftAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        launchConfigDriftAnalysis(project)
    }
}

/**
 * Runs the analysis off the EDT and publishes the result.
 *
 * A plain function rather than logic living inside [AnalyzeConfigDriftAction], because the tool
 * window's Rerun button needs the same behaviour. Calling `AnAction.actionPerformed` directly to
 * reuse it is not allowed — that method is `@ApiStatus.OverrideOnly`, and the JetBrains Plugin
 * Verifier fails the build on it.
 */
fun launchConfigDriftAnalysis(project: Project) {
    val service = project.service<ConfigDriftService>()

    object : Task.Backgroundable(project, "Analyzing Spring config drift", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            val report = service.analyze()

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                service.publish(report)
                ToolWindowManager.getInstance(project)
                    .getToolWindow(DRIFT_TOOL_WINDOW_ID)
                    ?.activate(null)
            }
        }
    }.queue()
}

/** Must match the `id` of the toolWindow extension in plugin.xml. */
const val DRIFT_TOOL_WINDOW_ID = "Config Drift"
