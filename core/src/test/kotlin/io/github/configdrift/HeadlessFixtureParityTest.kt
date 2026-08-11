package io.github.configdrift

import io.github.configdrift.model.MetadataContractMismatch
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.SecretExposure
import io.github.configdrift.model.Severity
import io.github.configdrift.model.ShapeMismatch
import io.github.configdrift.model.UnresolvedPlaceholder
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the headless pipeline against [plugin/testFixtures/sample-spring-project] and asserts the
 * must-pass finding types/keys from EXPECTED.md (offsets may differ from the IDE PSI path).
 */
class HeadlessFixtureParityTest {

    private val fixture: Path = Path(
        System.getProperty("configdrift.fixture")
            ?: error("configdrift.fixture system property is not set"),
    )

    @Test
    fun sampleFixtureProducesExpectedFindingKinds() {
        assertTrue(fixture.toFile().isDirectory, "fixture missing at $fixture")

        val report = HeadlessAnalyzer.analyze(fixture)
        val byType = report.findings.groupBy { it::class }

        assertTrue(byType[SecretExposure::class]!!.isNotEmpty(), "expected SecretExposure findings")
        assertTrue(
            report.findings.any {
                it is SecretExposure && it.key.value == "spring.datasource.password"
            },
            "dev password should be flagged",
        )
        assertTrue(
            report.findings.none {
                it is SecretExposure &&
                    it.key.value == "spring.datasource.password" &&
                    it.profile.name == "prod"
            },
            "prod \${DB_PASSWORD} must not be a SecretExposure",
        )
        assertTrue(
            report.findings.any {
                it is SecretExposure && it.ruleId == "aws-access-key-id"
            },
            "prod AWS example key should be flagged",
        )

        val timeout = report.findings.filterIsInstance<ShapeMismatch>()
            .firstOrNull { it.key.value == "app.feature.timeout" }
        assertNotNull(timeout, "app.feature.timeout shape drift")

        val verbose = report.findings.filterIsInstance<MissingKey>()
            .firstOrNull { it.key.value == "app.debug.verbose" }
        assertNotNull(verbose, "app.debug.verbose missing-key")
        assertTrue("prod" in verbose.missingIn.map { it.name })
        assertTrue("stage" in verbose.missingIn.map { it.name })
        assertTrue("default" !in verbose.missingIn.map { it.name })

        assertTrue(
            report.findings.any {
                it is UnresolvedPlaceholder &&
                    it.placeholder == "DB_HOST" &&
                    it.severity != Severity.ERROR
            },
            "DB_HOST placeholder must not be ERROR",
        )

        assertTrue(
            report.findings.any {
                it is MetadataContractMismatch &&
                    it.kind == MetadataContractMismatch.Kind.DECLARED_NOT_SET
            },
            "expected DECLARED_NOT_SET from additional-spring-configuration-metadata.json",
        )

        val json = io.github.configdrift.report.JsonReportRenderer().render(report)
        assertTrue("devpassword" !in json, "plaintext secret must not appear in JSON export")
        assertTrue("changeme" !in json, "placeholder default secret must not appear in JSON export")

        // Fail-on error semantics used by the CLI.
        assertTrue(report.findingsBySeverity().getValue(Severity.ERROR).isNotEmpty())
        assertEquals(
            report.findings.count { it.severity == Severity.ERROR },
            report.findingsBySeverity().getValue(Severity.ERROR).size,
        )
    }
}
