package io.github.configdrift.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.configdrift.ConfigDriftService
import io.github.configdrift.discovery.ConfigFormats
import io.github.configdrift.discovery.ProjectPaths
import io.github.configdrift.model.Finding
import io.github.configdrift.model.Severity

/**
 * Shows the latest analysis result as editor highlights.
 *
 * This inspection computes nothing. Drift is a cross-file property — knowing that `app.debug`
 * exists in dev but not prod requires reading four files — and a `LocalInspectionTool` is handed
 * one file at a time, so doing the comparison here would mean re-analysing the whole project on
 * every keystroke. Instead [ConfigDriftService] owns the (cached) analysis and this class only
 * projects the findings that belong to the file being edited onto its PSI.
 *
 * The consequence is honest and worth knowing: highlights reflect the last completed analysis, not
 * the buffer as it is being typed. [io.github.configdrift.ConfigFileChangeListener] keeps that
 * gap small by re-running on save.
 */
class ConfigDriftInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        val virtualFile = file.virtualFile ?: return null
        if (!ConfigFormats.isKnownConfigFile(virtualFile.name)) return null

        val project = file.project
        val report = project.service<ConfigDriftService>().lastReport ?: return null
        val relativePath = ProjectPaths.relativize(project.basePath, virtualFile.path)

        val descriptors = report.findings.mapNotNull { finding ->
            val location = finding.location ?: return@mapNotNull null
            if (location.filePath != relativePath) return@mapNotNull null

            // Offsets come from the last analysis, so an edit can push them past the current end
            // of file. findElementAt returns null there rather than throwing, which is the
            // behaviour we want: skip the stale highlight instead of reporting at a wrong place.
            val element = file.findElementAt(location.offset) ?: return@mapNotNull null

            // No dismiss action for a secret exposure — see Finding.suppressible.
            val fixes: Array<LocalQuickFix> =
                if (finding.suppressible) arrayOf(SuppressFindingQuickFix(finding))
                else LocalQuickFix.EMPTY_ARRAY

            // A YAML/Properties key's PSI element is already line-sized, so clipping to the
            // current line is a no-op there. A format with no PSI language of its own (.env — see
            // ParseSupport.locationOf's offset overload) parses as a single PsiPlainTextFile leaf
            // spanning the *entire* file, so without clipping, highlighting `element` as-is would
            // underline the whole file instead of the one line the finding is actually about.
            val rangeInElement = lineClippedRange(file, location.offset, element.textRange)

            if (rangeInElement != null) {
                manager.createProblemDescriptor(
                    element,
                    rangeInElement,
                    finding.message,
                    highlightTypeOf(finding.severity),
                    isOnTheFly,
                    *fixes,
                )
            } else {
                manager.createProblemDescriptor(
                    element,
                    finding.message,
                    isOnTheFly,
                    fixes,
                    highlightTypeOf(finding.severity),
                )
            }
        }

        return descriptors.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * The finding's offset clipped to its own line, and expressed relative to [elementRange] as
     * `createProblemDescriptor`'s `rangeInElement` parameter requires — `null` when the document
     * is unavailable, or when [InspectionHighlightRanges.lineClippedRangeInElement] finds no
     * overlap, in which case the caller falls back to highlighting the whole element rather than
     * nothing.
     */
    private fun lineClippedRange(file: PsiFile, offset: Int, elementRange: TextRange): TextRange? {
        val document = file.viewProvider.document ?: return null
        val lineIndex = document.getLineNumber(offset)
        return InspectionHighlightRanges.lineClippedRangeInElement(
            lineStart = document.getLineStartOffset(lineIndex),
            lineEnd = document.getLineEndOffset(lineIndex),
            elementRange = elementRange,
        )
    }

    private fun highlightTypeOf(severity: Severity): ProblemHighlightType = when (severity) {
        Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR
        Severity.WARNING -> ProblemHighlightType.WARNING
        Severity.INFO -> ProblemHighlightType.WEAK_WARNING
    }
}

/**
 * Alt+Enter on a highlight dismisses that finding, reusing the same suppression store as the tool
 * window's right-click menu — so a finding dismissed from the editor also disappears from the
 * report, and can be brought back from the Suppressed tab.
 */
private class SuppressFindingQuickFix(private val finding: Finding) : LocalQuickFix {

    override fun getFamilyName(): String = "Suppress this Config Drift finding"

    // Suppression writes a settings value, not the document, so no write action is needed.
    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        // ConfigDriftService.suppress() restarts the daemon itself, so the highlight this quick
        // fix was invoked from disappears without any extra step here.
        project.service<ConfigDriftService>().suppress(finding)
    }
}
