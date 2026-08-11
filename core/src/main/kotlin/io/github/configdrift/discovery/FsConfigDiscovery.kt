package io.github.configdrift.discovery

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Locates config and metadata files on the filesystem (CLI / headless).
 *
 * Mirrors VFS [io.github.configdrift.discovery.ConfigFileDiscovery] rules: skip build output for
 * config files, but allow it for generated `spring-configuration-metadata.json`. Results are
 * sorted by path for stable last-wins.
 */
object FsConfigDiscovery {

    private val ALWAYS_SKIP_DIRS = setOf(
        ".git", ".gradle", "node_modules", ".idea", ".svn", ".hg", "__pycache__",
    )

    private val BUILD_DIRS = setOf("build", "out", "target")

    private val METADATA_FILE_NAMES = setOf(
        "spring-configuration-metadata.json",
        "additional-spring-configuration-metadata.json",
    )

    fun discoverConfigFiles(root: Path): List<Path> =
        collect(root, skipBuildOutput = true) { path ->
            ConfigFormats.isKnownConfigFile(path.name)
        }

    fun discoverMetadataFiles(root: Path): List<Path> =
        collect(root, skipBuildOutput = false) { path ->
            path.name in METADATA_FILE_NAMES && path.parent?.name == "META-INF"
        }

    private fun collect(
        root: Path,
        skipBuildOutput: Boolean,
        matcher: (Path) -> Boolean,
    ): List<Path> {
        val result = mutableListOf<Path>()
        if (!Files.isDirectory(root)) return emptyList()

        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name in ALWAYS_SKIP_DIRS) return FileVisitResult.SKIP_SUBTREE
                    if (skipBuildOutput && name in BUILD_DIRS) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.isRegularFile() && matcher(file)) {
                        result.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )

        return result.sortedBy { it.toAbsolutePath().toString().replace('\\', '/') }
    }
}
