package io.github.configdrift.engine

import io.github.configdrift.model.Finding
import io.github.configdrift.model.ShapeMismatch
import io.github.configdrift.model.ValueShape

/**
 * Reports a key whose type or structure differs between environments — `timeout: 30` in dev and
 * `timeout: "30s"` in prod, or a scalar in one profile and a list in another.
 *
 * Numeric widening is tolerated: INTEGER against DECIMAL is not drift, because both bind to the
 * same target for any numeric property. NULL is also tolerated, since an explicitly empty value
 * is a deliberate override rather than a type error.
 *
 * Scalar-vs-object conflicts are handled separately via [StructuralConflict], because flattening
 * hides them from a plain per-key comparison — see [structuralFindings].
 */
class ShapeMismatchAnalyzer : DriftAnalyzer {

    override val id: String = "shape-mismatch"

    override fun analyze(context: AnalysisContext): List<Finding> =
        valueShapeFindings(context) + structuralFindings(context)

    private fun valueShapeFindings(context: AnalysisContext): List<Finding> {
        val findings = mutableListOf<Finding>()

        for (key in context.snapshot.allKeys) {
            val occurrences = context.snapshot.profiles.mapNotNull { profileSnapshot ->
                val entry = profileSnapshot.byKey[key] ?: return@mapNotNull null
                ShapeMismatch.Occurrence(
                    profile = profileSnapshot.profile,
                    shape = entry.shape,
                    location = entry.location,
                )
            }
            if (occurrences.size < 2) continue

            val meaningful = occurrences.filter { it.shape != ValueShape.NULL }
            if (meaningful.size < 2) continue

            if (!isConsistent(meaningful.map { it.shape })) {
                findings += ShapeMismatch(key = key, occurrences = occurrences)
            }
        }
        return findings
    }

    /**
     * Reports keys that are a plain value in one profile and a nested object in another.
     *
     * The container side has no entry of its own to read a shape from — flattening only produced
     * its children — so its occurrence is synthesised as [ValueShape.MAP] and anchored at the
     * first child key, which is the nearest thing the user can navigate to.
     */
    private fun structuralFindings(context: AnalysisContext): List<Finding> =
        context.structurallyConflictingKeys.mapNotNull { key ->
            val prefix = key.value + "."
            val occurrences = context.snapshot.profiles.mapNotNull { profileSnapshot ->
                val leaf = profileSnapshot.byKey[key]
                if (leaf != null) {
                    return@mapNotNull ShapeMismatch.Occurrence(
                        profile = profileSnapshot.profile,
                        shape = leaf.shape,
                        location = leaf.location,
                    )
                }
                val firstChild = profileSnapshot.entries
                    .filter { it.key.value.startsWith(prefix) }
                    .minByOrNull { it.key.value }
                    ?: return@mapNotNull null

                ShapeMismatch.Occurrence(
                    profile = profileSnapshot.profile,
                    shape = ValueShape.MAP,
                    location = firstChild.location,
                )
            }
            if (occurrences.size < 2) null else ShapeMismatch(key = key, occurrences = occurrences)
        }

    private fun isConsistent(shapes: List<ValueShape>): Boolean {
        val distinct = shapes.toSet()
        if (distinct.size <= 1) return true
        // INTEGER/DECIMAL both bind to any numeric target; nothing else is interchangeable.
        return distinct == NUMERIC
    }

    private companion object {
        val NUMERIC = setOf(ValueShape.INTEGER, ValueShape.DECIMAL)
    }
}
