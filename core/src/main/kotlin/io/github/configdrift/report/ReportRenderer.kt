package io.github.configdrift.report

import io.github.configdrift.model.DriftReport

/**
 * Renders a [DriftReport] to text.
 *
 * Renderers receive an already-masked report, so there is no masking responsibility here and no
 * way for a new renderer to introduce a leak by forgetting one.
 */
interface ReportRenderer {
    val id: String
    val fileExtension: String

    fun render(report: DriftReport): String
}
