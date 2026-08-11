package io.github.configdrift.engine

/**
 * Manual OverlayHeuristic overrides for a single analysis run.
 *
 * Profile names as plain strings so CLI flags and IDE settings share the same shape.
 */
data class OverlayOverrides(
    val manualComplete: Set<String> = emptySet(),
    val manualOverlay: Set<String> = emptySet(),
) {
    companion object {
        val NONE = OverlayOverrides()
    }
}
