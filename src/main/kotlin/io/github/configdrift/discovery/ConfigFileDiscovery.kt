package io.github.configdrift.discovery

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import io.github.configdrift.parser.ProfileResolver

/**
 * Locates the files the analysis runs on.
 *
 * Walks content roots directly rather than going through a filename index, because the two
 * searches need opposite treatment of build output: config files must *not* be picked up from
 * `build/`, while generated metadata lives there and nowhere else.
 */
class ConfigFileDiscovery {

    fun discoverConfigFiles(project: Project): List<VirtualFile> =
        collect(project, skipBuildOutput = true) { file ->
            ProfileResolver.isConfigFileName(file.name)
        }

    /**
     * `spring-configuration-metadata.json` is produced by the annotation processor into the
     * compiler output; `additional-spring-configuration-metadata.json` is hand-written in
     * resources. Both are the *project's own* metadata.
     *
     * Metadata from dependency jars is deliberately out of scope for the MVP: including it would
     * make "set but not declared" quieter but "declared but never set" enormously noisy, since
     * every unused property of every starter on the classpath would qualify.
     */
    fun discoverMetadataFiles(project: Project): List<VirtualFile> =
        collect(project, skipBuildOutput = false) { file ->
            file.name in METADATA_FILE_NAMES && file.parent?.name == "META-INF"
        }

    private fun collect(
        project: Project,
        skipBuildOutput: Boolean,
        matcher: (VirtualFile) -> Boolean,
    ): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            VfsUtilCore.visitChildrenRecursively(
                root,
                object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (file.isDirectory) {
                            if (file.name in ALWAYS_SKIPPED) return false
                            if (skipBuildOutput && file.name in BUILD_OUTPUT_DIRS) return false
                            return true
                        }
                        if (matcher(file)) result += file
                        return true
                    }
                },
            )
        }
        return result
    }

    private companion object {
        val METADATA_FILE_NAMES = setOf(
            "spring-configuration-metadata.json",
            "additional-spring-configuration-metadata.json",
        )
        val ALWAYS_SKIPPED = setOf(".git", ".gradle", ".idea", "node_modules")
        val BUILD_OUTPUT_DIRS = setOf("build", "out", "target")
    }
}
