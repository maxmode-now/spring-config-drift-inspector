package io.github.configdrift.ui

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.github.configdrift.ConfigDriftService
import io.github.configdrift.parser.ProfileResolver

/**
 * Entry point: Tools | Analyze Spring Config Drift, plus a right-click shortcut from the Project
 * view and the editor when the file under the cursor is one this plugin actually looks at.
 *
 * The analysis itself is always whole-project — right-clicking `application-prod.yml` does not
 * scope the run to that one file — so the popup-menu registrations exist purely to save a trip to
 * the Tools menu when the user is already looking at a config file, not to imply file-scoped
 * analysis.
 */
class AnalyzeConfigDriftAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (e.project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // Only the two popup-menu placements are filtered by file type. The Tools-menu placement
        // (and anywhere else this action can be reached, like Find Action) must stay visible
        // regardless of which file happens to be focused — gating on CommonDataKeys.VIRTUAL_FILE
        // unconditionally would make the Tools-menu item disappear whenever a .java file is the
        // active editor, since that data key resolves to whatever file has focus, not to "no
        // file" just because the user opened the item through a menu bar.
        if (e.place != ActionPlaces.EDITOR_POPUP && e.place != ActionPlaces.PROJECT_VIEW_POPUP) {
            e.presentation.isEnabledAndVisible = true
            return
        }

        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && ProfileResolver.isConfigFileName(file.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        launchConfigDriftAnalysis(project)
    }
}

/**
 * A plain function rather than logic living inside [AnalyzeConfigDriftAction], because the tool
 * window's Rerun button needs the same behaviour. Calling `AnAction.actionPerformed` directly to
 * reuse it is not allowed — that method is `@ApiStatus.OverrideOnly`, and the JetBrains Plugin
 * Verifier fails the build on it.
 *
 * Delegates the actual run to [ConfigDriftService.analyzeInBackgroundAndPublish] rather than
 * queuing its own `Task.Backgroundable`: that used to be two independent, uncoordinated code
 * paths (this one, and the one behind automatic re-analysis), and whichever happened to finish
 * last would publish — even if it had started earlier and reflected staler file state. Going
 * through the one shared entry point is what lets that method's sequence-number guard cover both
 * triggers instead of just the one that remembered to check.
 */
fun launchConfigDriftAnalysis(project: Project) {
    project.service<ConfigDriftService>().analyzeInBackgroundAndPublish {
        ToolWindowManager.getInstance(project)
            .getToolWindow(DRIFT_TOOL_WINDOW_ID)
            ?.activate(null)
    }
}

/** Must match the `id` of the toolWindow extension in plugin.xml. */
const val DRIFT_TOOL_WINDOW_ID = "Config Drift"
