package io.github.configdrift.report

import io.github.configdrift.metadata.MiniJson
import io.github.configdrift.model.CellState
import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.Finding
import io.github.configdrift.model.MetadataContractMismatch
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.OverlayProfileExcluded
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.SecretExposure
import io.github.configdrift.model.ShapeMismatch
import io.github.configdrift.model.SourceLocation
import io.github.configdrift.model.UnresolvedPlaceholder
import io.github.configdrift.model.ValueShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The renderer builds JSON by hand (see the class doc for why), which means nothing catches a
 * broken escape or a stray comma except actually parsing the output back — a syntax error here
 * would otherwise only surface once a real CI script chokes on it.
 *
 * [MiniJson] is used as the parser precisely because it is a second, independent implementation
 * from the [JsonReportRenderer]'s string-builder: a mistake shared between "how we write a
 * string" and "how we read one back" is far less likely than a mistake in just one of them.
 */
class JsonReportRendererTest {

    private val renderer = JsonReportRenderer()
    private val here = SourceLocation("a.yml", 1, 0)

    @Test
    fun `output is valid, parseable JSON for one of every finding type`() {
        val findings: List<Finding> = listOf(
            MissingKey(
                NormalizedKey("a"), listOf(ProfileId("prod")), listOf(ProfileId("dev")), here,
            ),
            ShapeMismatch(
                NormalizedKey("b"),
                listOf(
                    ShapeMismatch.Occurrence(ProfileId("dev"), ValueShape.INTEGER, here),
                    ShapeMismatch.Occurrence(ProfileId("prod"), ValueShape.STRING, here),
                ),
            ),
            SecretExposure(NormalizedKey("c"), ProfileId("dev"), here, "password", "***"),
            UnresolvedPlaceholder(
                NormalizedKey("d"), ProfileId("prod"), here, "DB_HOST", false,
                UnresolvedPlaceholder.Kind.EXTERNAL_ENVIRONMENT,
            ),
            MetadataContractMismatch(
                NormalizedKey("e"), MetadataContractMismatch.Kind.DECLARED_NOT_SET,
                null, null, "java.lang.String", null,
            ),
            OverlayProfileExcluded(ProfileId("local"), ConfigDomain.SPRING, 1, 8, manual = false),
        )
        val report = DriftReport(
            projectName = "demo",
            generatedAtEpochMillis = 1_700_000_000_000L,
            profiles = listOf(ProfileId("default"), ProfileId("dev"), ProfileId("prod")),
            matrix = mapOf(
                NormalizedKey("a") to mapOf(ProfileId("dev") to CellState.SET),
            ),
            findings = findings,
        )

        val json = renderer.render(report)
        val parsed = MiniJson.parse(json)

        val root = assertIs<Map<*, *>>(parsed)
        assertEquals("demo", root["projectName"])
        assertEquals(6.0, (root["findings"] as List<*>).size.toDouble())
    }

    @Test
    fun `quotes, backslashes, newlines, and tabs in a message round-trip intact`() {
        val nasty = "line1\nline2\ttab \"quoted\" back\\slash"
        val report = reportWith(
            MissingKey(NormalizedKey("k"), listOf(ProfileId("prod")), listOf(ProfileId("dev")), here)
                .let { finding ->
                    // MissingKey's message is derived, not settable directly; SecretExposure's
                    // isn't either, so exercise escaping through a finding whose fields we control
                    // and that flows straight into rendered JSON: the location's file path.
                    finding.copy(location = here.copy(filePath = nasty))
                },
        )

        val parsed = MiniJson.parse(renderer.render(report))
        val finding = ((parsed as Map<*, *>)["findings"] as List<*>).single() as Map<*, *>
        val location = finding["location"] as Map<*, *>
        assertEquals(nasty, location["file"])
    }

    @Test
    fun `a control character is escaped rather than emitted raw`() {
        val withNul = here.copy(filePath = "weird\u0000path")
        val report = reportWith(
            MissingKey(NormalizedKey("k"), listOf(ProfileId("prod")), listOf(ProfileId("dev")), withNul),
        )

        val json = renderer.render(report)
        assertTrue("\\u0000" in json)
        val parsed = MiniJson.parse(json)
        val finding = ((parsed as Map<*, *>)["findings"] as List<*>).single() as Map<*, *>
        val location = finding["location"] as Map<*, *>
        assertEquals("weird\u0000path", location["file"])
    }

    @Test
    fun `summary lists every severity even at zero, so a CI script never reads undefined`() {
        val onlyWarning = MetadataContractMismatch(
            NormalizedKey("e"), MetadataContractMismatch.Kind.SET_NOT_DECLARED,
            here, ProfileId("prod"), null, ValueShape.STRING,
        )
        val report = reportWith(onlyWarning)

        val parsed = MiniJson.parse(renderer.render(report))
        val summary = (parsed as Map<*, *>)["summary"] as Map<*, *>

        assertEquals(0.0, summary["ERROR"])
        assertEquals(1.0, summary["WARNING"])
        assertEquals(0.0, summary["INFO"])
    }

    @Test
    fun `an empty findings list still renders valid JSON`() {
        val parsed = MiniJson.parse(renderer.render(reportWith()))
        assertEquals(emptyList<Any?>(), (parsed as Map<*, *>)["findings"])
    }

    private fun reportWith(vararg findings: Finding): DriftReport = DriftReport(
        projectName = "demo",
        generatedAtEpochMillis = 1_700_000_000_000L,
        profiles = listOf(ProfileId("default"), ProfileId("prod")),
        matrix = emptyMap(),
        findings = findings.toList(),
    )
}
