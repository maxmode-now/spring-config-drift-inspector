package io.github.configdrift.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.configdrift.model.SourceLocation

/**
 * Feature 9: jump from a finding to the exact element that produced it.
 *
 * Navigation is by character offset rather than line number, which is why parsers are built on
 * PSI — the offset lands on the key itself even in a deeply nested YAML mapping.
 */
object SourceNavigator {

    fun navigate(project: Project, location: SourceLocation) {
        val file = resolve(project, location) ?: return
        OpenFileDescriptor(project, file, location.offset).navigate(true)
    }

    /** Locations are stored project-relative when possible, so try that first. */
    private fun resolve(project: Project, location: SourceLocation): VirtualFile? {
        val fileSystem = LocalFileSystem.getInstance()
        val base = project.basePath
        val normalized = location.filePath.replace('\\', '/')
        return base?.let { fileSystem.findFileByPath("$it/$normalized") }
            ?: fileSystem.findFileByPath(normalized)
    }
}
