package io.github.configdrift.report

import io.github.configdrift.metadata.MiniJson
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.SecretExposure
import io.github.configdrift.model.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SarifReportRendererTest {

    private val renderer = SarifReportRenderer()
    private val here = SourceLocation("src/main/resources/application-dev.yml", 5, 10)

    @Test
    fun `output is valid SARIF 2_1 with mapped levels and locations`() {
        val report = DriftReport(
            projectName = "demo",
            generatedAtEpochMillis = 1L,
            profiles = listOf(ProfileId("dev"), ProfileId("prod")),
            matrix = emptyMap(),
            findings = listOf(
                MissingKey(
                    NormalizedKey("app.debug.verbose"),
                    listOf(ProfileId("prod")),
                    listOf(ProfileId("dev")),
                    here,
                ),
                SecretExposure(
                    NormalizedKey("spring.datasource.password"),
                    ProfileId("dev"),
                    here,
                    "password",
                    "???????? (len=11)",
                ),
            ),
        )

        val parsed = assertIs<Map<*, *>>(MiniJson.parse(renderer.render(report)))
        assertEquals("2.1.0", parsed["version"])

        val runs = assertIs<List<*>>(parsed["runs"])
        val run = assertIs<Map<*, *>>(runs.single())
        val driver = assertIs<Map<*, *>>(assertIs<Map<*, *>>(run["tool"])["driver"])
        assertEquals("config-drift", driver["name"])

        val rules = assertIs<List<*>>(driver["rules"])
        val ruleIds = rules.map { assertIs<Map<*, *>>(it)["id"] }.toSet()
        assertEquals(setOf("MissingKey", "SecretExposure"), ruleIds)

        val results = assertIs<List<*>>(run["results"])
        assertEquals(2, results.size)

        val missing = assertIs<Map<*, *>>(results[0])
        assertEquals("MissingKey", missing["ruleId"])
        assertEquals("error", missing["level"])
        val loc = assertIs<Map<*, *>>(
            assertIs<Map<*, *>>(
                assertIs<List<*>>(missing["locations"]).single(),
            )["physicalLocation"],
        )
        assertEquals(
            "src/main/resources/application-dev.yml",
            assertIs<Map<*, *>>(loc["artifactLocation"])["uri"],
        )
        assertEquals(5.0, assertIs<Map<*, *>>(loc["region"])["startLine"])

        val secret = assertIs<Map<*, *>>(results[1])
        assertEquals("SecretExposure", secret["ruleId"])
        val message = assertIs<Map<*, *>>(secret["message"])["text"] as String
        assertTrue("devpassword" !in message)
        assertTrue("????????" in message)
    }

    @Test
    fun `finding without location omits locations array`() {
        val report = DriftReport(
            projectName = "demo",
            generatedAtEpochMillis = 1L,
            profiles = emptyList(),
            matrix = emptyMap(),
            findings = listOf(
                io.github.configdrift.model.MetadataContractMismatch(
                    NormalizedKey("app.unused"),
                    io.github.configdrift.model.MetadataContractMismatch.Kind.DECLARED_NOT_SET,
                    null,
                    null,
                    "java.lang.String",
                    null,
                ),
            ),
        )
        val result = assertIs<Map<*, *>>(
            assertIs<List<*>>(
                assertIs<Map<*, *>>(
                    assertIs<List<*>>(
                        assertIs<Map<*, *>>(MiniJson.parse(renderer.render(report)))["runs"],
                    ).single(),
                )["results"],
            ).single(),
        )
        assertEquals("note", result["level"])
        assertTrue("locations" !in result)
    }
}
