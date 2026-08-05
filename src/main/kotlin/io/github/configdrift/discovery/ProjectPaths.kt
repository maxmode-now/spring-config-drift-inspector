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
 * Pure on purpose: takes the base path as a string rather than a `Project`, so it is testable
 * without an IDE fixture.
 */
object ProjectPaths {

    fun relativize(basePath: String?, absolutePath: String): String {
        val normalizedPath = absolutePath.replace('\\', '/')
        val normalizedBase = basePath?.replace('\\', '/')?.trimEnd('/')
            ?: return normalizedPath

        if (!normalizedPath.startsWith("$normalizedBase/")) return normalizedPath
        return normalizedPath.removePrefix(normalizedBase).trimStart('/')
    }
}
