package io.github.configdrift

import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.configdrift.parser.ProfileResolver

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
 * Filters to `application*` files first: an unrelated file change must not trigger a whole-project
 * config analysis. The actual scheduling — and the debounce that keeps a burst of saves from
 * costing several runs — lives in [ConfigDriftService.requestReanalysis].
 */
class ConfigFileChangeListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        val affectedProjects = events.asSequence()
            .mapNotNull { it.file }
            .filter { ProfileResolver.isConfigFileName(it.name) }
            .flatMap { ProjectLocator.getInstance().getProjectsForFile(it).asSequence() }
            .filterNotNull()
            .toSet()

        for (project in affectedProjects) {
            if (!project.isDisposed) {
                project.service<ConfigDriftService>().requestReanalysis()
            }
        }
    }
}
