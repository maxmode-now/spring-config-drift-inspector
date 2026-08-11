package io.github.configdrift.inspection

import com.intellij.openapi.util.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression for the `.env` whole-file underline: PlainText PSI is one leaf spanning the file, so
 * highlighting the element without a line-clipped `rangeInElement` underlines everything.
 */
class InspectionHighlightRangesTest {

    @Test
    fun `plain-text whole-file element clips highlight to the finding line only`() {
        // ".env" content: "A=1\nDB_PASSWORD=secret\nC=3" — middle line is [4, 22)
        val fileRange = TextRange(0, 26)
        val range = InspectionHighlightRanges.lineClippedRangeInElement(
            lineStart = 4,
            lineEnd = 22,
            elementRange = fileRange,
        )
        assertEquals(TextRange(4, 22), range)
    }

    @Test
    fun `yaml-sized element entirely on one line is a relative no-op covering the element`() {
        // Key PSI [10, 18) on a longer line [0, 40)
        val elementRange = TextRange(10, 18)
        val range = InspectionHighlightRanges.lineClippedRangeInElement(
            lineStart = 0,
            lineEnd = 40,
            elementRange = elementRange,
        )
        assertEquals(TextRange(0, 8), range)
    }

    @Test
    fun `empty intersection falls back to null so the caller can highlight the whole element`() {
        assertNull(
            InspectionHighlightRanges.lineClippedRangeInElement(
                lineStart = 10,
                lineEnd = 10,
                elementRange = TextRange(0, 100),
            ),
        )
    }

    @Test
    fun `non-overlapping line and element returns null`() {
        assertNull(
            InspectionHighlightRanges.lineClippedRangeInElement(
                lineStart = 0,
                lineEnd = 5,
                elementRange = TextRange(20, 30),
            ),
        )
    }
}
