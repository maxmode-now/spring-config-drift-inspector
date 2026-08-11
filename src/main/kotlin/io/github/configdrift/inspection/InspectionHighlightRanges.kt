package io.github.configdrift.inspection

import com.intellij.openapi.util.TextRange

/**
 * Pure range math for [ConfigDriftInspection]'s `rangeInElement` — kept free of [PsiFile]/Document
 * so the `.env` whole-file clip (and the YAML/Properties no-op) can be unit-tested without an IDE
 * fixture.
 */
object InspectionHighlightRanges {

    /**
     * Absolute [lineStart], [lineEnd) intersected with [elementRange], then shifted to be relative
     * to [elementRange] as `createProblemDescriptor(..., rangeInElement, ...)` requires.
     *
     * Returns `null` when there is no overlap or the intersection is empty — the caller then falls
     * back to highlighting the whole element.
     */
    fun lineClippedRangeInElement(lineStart: Int, lineEnd: Int, elementRange: TextRange): TextRange? {
        val clipped = TextRange(lineStart, lineEnd).intersection(elementRange) ?: return null
        return clipped.takeUnless { it.isEmpty }?.shiftLeft(elementRange.startOffset)
    }
}
