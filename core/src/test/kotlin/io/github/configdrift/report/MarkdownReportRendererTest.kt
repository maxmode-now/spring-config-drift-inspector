package io.github.configdrift.report

import io.github.configdrift.model.CellState
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.SourceLocation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownReportRendererTest {

    private val dev = ProfileId("dev")
    private val prod = ProfileId("prod")
    private val key = NormalizedKey("app.debug.verbose")

    private val report = DriftReport(
        projectName = "demo",
        generatedAtEpochMillis = 1L,
        profiles = listOf(dev, prod),
        matrix = mapOf(
            key to mapOf(dev to CellState.SET, prod to CellState.MISSING),
        ),
        findings = listOf(
            MissingKey(
                key,
                listOf(prod),
                listOf(dev),
                SourceLocation("application-dev.yml", 1, 1),
            ),
        ),
    )

    @Test
    fun `default render includes key matrix`() {
        val md = MarkdownReportRenderer().render(report)
        assertTrue("## Summary" in md)
        assertTrue("## Findings" in md)
        assertTrue("## Key matrix" in md)
        assertTrue("`app.debug.verbose`" in md)
    }

    @Test
    fun `includeMatrix false omits key matrix but keeps summary and findings`() {
        val md = MarkdownReportRenderer(includeMatrix = false).render(report)
        assertTrue("## Summary" in md)
        assertTrue("## Findings" in md)
        assertTrue("No drift detected." !in md)
        assertTrue("app.debug.verbose" in md)
        assertFalse("## Key matrix" in md)
    }
}
