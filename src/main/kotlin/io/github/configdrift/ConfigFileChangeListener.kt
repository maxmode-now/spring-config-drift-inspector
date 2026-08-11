package io.github.configdrift

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
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

    /**
     * Populated by [before] for a directory-delete event whose contents (checked while the VFS
     * tree still existed) included a config file — see [before]'s KDoc for why that check can't
     * happen in [after]. `before`/`after` are always called back to back, synchronously, for the
     * same event batch, on the same thread, so a plain field is enough; no two batches ever
     * overlap here.
     */
    private var projectsFromDeletedConfigDirs: Set<Project> = emptySet()

    /**
     * A directory being deleted loses its children the moment the delete is applied — [after]
     * would see only that *something* named e.g. `resources` disappeared, with no way left to ask
     * whether it used to contain an `application-prod.yml`. `before` fires while the tree is still
     * intact, so it's the only place a whole-directory delete can be recognised as config-relevant
     * at all; `nameLooksLikeConfig`'s basename check alone would silently ignore it, since a
     * directory's own name is never a config file name.
     *
     * Filtered to [VFileDeleteEvent] first, same reasoning as [nameLooksLikeConfig]: this method
     * runs for every event in a batch that can be tens of thousands large, so the recursive child
     * walk below must stay reachable only by the rare directory-delete case, not by every event.
     */
    override fun before(events: List<VFileEvent>) {
        projectsFromDeletedConfigDirs = events.asSequence()
            .filterIsInstance<VFileDeleteEvent>()
            .mapNotNull { it.file }
            .filter { it.isDirectory && containsConfigFile(it) }
            .flatMap { ProjectLocator.getInstance().getProjectsForFile(it).asSequence() }
            .filterNotNull()
            .toSet()
    }

    override fun after(events: List<VFileEvent>) {
        val affectedProjects = events.asSequence()
            // Name check before touching getFile(), never after — see nameLooksLikeConfig.
            .filter(::nameLooksLikeConfig)
            .mapNotNull { it.file }
            .flatMap { ProjectLocator.getInstance().getProjectsForFile(it).asSequence() }
            .filterNotNull()
            .toSet() + projectsFromDeletedConfigDirs
        projectsFromDeletedConfigDirs = emptySet()

        for (project in affectedProjects) {
            if (!project.isDisposed) {
                project.service<ConfigDriftService>().requestReanalysis()
            }
        }
    }

    private fun containsConfigFile(dir: VirtualFile): Boolean {
        var found = false
        VfsUtilCore.visitChildrenRecursively(
            dir,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory && ConfigFormats.isKnownConfigFile(file.name)) {
                        found = true
                    }
                    return !found
                }
            },
        )
        return found
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
