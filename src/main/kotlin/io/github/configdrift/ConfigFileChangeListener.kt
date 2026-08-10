package io.github.configdrift

import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.configdrift.discovery.ConfigFormats

/**
 * Re-runs the analysis when a configuration file changes on disk, so the editor highlights stay
 * roughly in step with the files without the user invoking anything.
 *
 * Registered as an `applicationListeners` entry, not `projectListeners`: VFS change events fire on
 * the application message bus regardless of which project (if any) is open, so a per-project
 * registration would compile and install without error but never actually receive an event. That
 * is also why this class takes no constructor argument and resolves the owning project itself, via
 * [ProjectLocator], for each changed file.
 *
 * Filters to files [ConfigFormats] recognizes first: an unrelated file change must not trigger a
 * whole-project config analysis. The actual scheduling — and the debounce that keeps a burst of
 * saves from costing several runs — lives in [ConfigDriftService.requestReanalysis].
 */
class ConfigFileChangeListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        val affectedProjects = events.asSequence()
            // Name check before touching getFile(), never after — see nameLooksLikeConfig.
            .filter(::nameLooksLikeConfig)
            .mapNotNull { it.file }
            .flatMap { ProjectLocator.getInstance().getProjectsForFile(it).asSequence() }
            .filterNotNull()
            .toSet()

        for (project in affectedProjects) {
            if (!project.isDisposed) {
                project.service<ConfigDriftService>().requestReanalysis()
            }
        }
    }

    /**
     * Decides relevance from the event's path alone, without resolving the [VirtualFile].
     *
     * `after()` runs on the EDT inside the write action that applied the change, and receives
     * *every* VFS event in the IDE — a Gradle build or a branch switch delivers one batch holding
     * tens of thousands. `VFileCreateEvent.getFile()` is not a field read: it resolves the file
     * with `parent.findChild(name)`, a real VFS child lookup. Calling it once per event, as this
     * used to, put that lookup on the EDT tens of thousands of times for a batch in which almost
     * nothing is a config file. `VFileEvent.getPath()` is a cached string and costs nothing, so
     * the cheap check runs first and `getFile()` is reached only by the handful that matter.
     *
     * A rename needs the extra case: its path is the *old* one, so a file renamed *into*
     * `application-prod.yml` would otherwise be ignored until the next unrelated edit.
     */
    private fun nameLooksLikeConfig(event: VFileEvent): Boolean {
        if (isConfigPath(event.path)) return true
        return event is VFilePropertyChangeEvent && event.isRename && isConfigPath(event.newPath)
    }

    private fun isConfigPath(path: String): Boolean =
        ConfigFormats.isKnownConfigFile(path.substringAfterLast('/'))
}
