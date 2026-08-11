package io.github.configdrift.discovery

/**
 * Turns an absolute file path into the project-relative form stored in
 * [io.github.configdrift.model.SourceLocation.filePath].
 *
 * Shared between the parser (which writes these paths) and the inspection (which matches the file
 * being edited against them). If the two computed paths differently — a stray separator, a
 * different case — no highlight would ever appear and nothing would report an error, so the
 * calculation deliberately lives in exactly one place.
 *
 * The prefix match is deliberately case-insensitive. Windows' filesystem is case-preserving but
 * case-insensitive, so the same real file can come back with different casing depending on which
 * IntelliJ API reported it — `Project.basePath` and a `VirtualFile.path` for a file under it are
 * not guaranteed to agree, e.g. a lowercase versus uppercase drive letter.
 *
 * What a failed match costs is worth stating precisely, because it is narrower than it looks.
 * Highlighting survives it: [io.github.configdrift.inspection.ConfigDriftInspection] compares the
 * finding's stored path against a path it computes through *this same function*, both from a
 * `VirtualFile.path`, so the two agree whether or not the base was stripped. Navigation survives
 * it too — [io.github.configdrift.ui.SourceNavigator] falls back to resolving `filePath` as-is
 * when prepending the base path yields nothing. What is left is that the path stays absolute, so
 * the Location column and both exported reports carry the developer's full local directory
 * layout — noise in the tool window, and something a Markdown report pasted into a pull request
 * has no business containing.
 *
 * On a genuinely case-sensitive filesystem (Linux, macOS) this relaxation is harmless: every path
 * handed to this function already comes from walking the project's own content roots, so a
 * same-file divergence in case never arises there in the first place.
 *
 * Pure on purpose: takes the base path as a string rather than a `Project`, so it is testable
 * without an IDE fixture.
 */
object ProjectPaths {

    fun relativize(basePath: String?, absolutePath: String): String {
        val normalizedPath = absolutePath.replace('\\', '/')
        val normalizedBase = basePath?.replace('\\', '/')?.trimEnd('/')
            ?: return normalizedPath

        val prefix = "$normalizedBase/"
        if (!normalizedPath.startsWith(prefix, ignoreCase = true)) return normalizedPath
        return normalizedPath.substring(prefix.length)
    }
}
