package io.github.configdrift.engine

import io.github.configdrift.model.MetadataContractMismatch
import io.github.configdrift.model.MissingKey
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.SecretExposure
import io.github.configdrift.model.SourceLocation
import io.github.configdrift.model.ValueShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FindingFingerprintTest {

    private val here = SourceLocation("a.yml", 1, 0)
    private val elsewhere = SourceLocation("a.yml", 99, 5000)

    @Test
    fun `location does not affect the fingerprint`() {
        val key = NormalizedKey("app.debug.verbose")
        val a = MissingKey(key, missingIn = listOf(ProfileId("prod")), presentIn = listOf(ProfileId("dev")), location = here)
        val b = a.copy(location = elsewhere)
        assertEquals(FindingFingerprint.of(a), FindingFingerprint.of(b))
    }

    @Test
    fun `missingIn and presentIn changing does not affect the fingerprint`() {
        // A key that starts missing from one profile and later also drops from a second is
        // still "the same finding" from the user's point of view — the suppression should hold.
        val key = NormalizedKey("app.debug.verbose")
        val a = MissingKey(key, missingIn = listOf(ProfileId("prod")), presentIn = listOf(ProfileId("dev")), location = here)
        val b = MissingKey(
            key,
            missingIn = listOf(ProfileId("prod"), ProfileId("stage")),
            presentIn = listOf(ProfileId("dev")),
            location = here,
        )
        assertEquals(FindingFingerprint.of(a), FindingFingerprint.of(b))
    }

    @Test
    fun `different keys produce different fingerprints`() {
        val a = MissingKey(NormalizedKey("a"), listOf(ProfileId("prod")), listOf(ProfileId("dev")), here)
        val b = MissingKey(NormalizedKey("b"), listOf(ProfileId("prod")), listOf(ProfileId("dev")), here)
        assertNotEquals(FindingFingerprint.of(a), FindingFingerprint.of(b))
    }

    @Test
    fun `secret exposure is scoped by profile and rule, not just key`() {
        val key = NormalizedKey("spring.datasource.password")
        val devPassword = SecretExposure(key, ProfileId("dev"), here, ruleId = "password", masked = "***")
        val stagePassword = devPassword.copy(profile = ProfileId("stage"))
        val devAwsKey = devPassword.copy(ruleId = "aws-access-key-id")

        assertNotEquals(FindingFingerprint.of(devPassword), FindingFingerprint.of(stagePassword))
        assertNotEquals(FindingFingerprint.of(devPassword), FindingFingerprint.of(devAwsKey))
    }

    @Test
    fun `secret exposures cannot be suppressed while other findings can`() {
        // Dismissing a committed credential is never the right fix, and persisting the dismissal
        // would record the key and profile of every hardcoded secret in a project file.
        val secret = SecretExposure(
            NormalizedKey("spring.datasource.password"), ProfileId("dev"), here, "password", "***",
        )
        val missing = MissingKey(
            NormalizedKey("app.debug"), listOf(ProfileId("prod")), listOf(ProfileId("dev")), here,
        )

        assertFalse(secret.suppressible)
        assertTrue(missing.suppressible)
    }

    @Test
    fun `metadata contract mismatch is scoped by kind`() {
        val key = NormalizedKey("app.mail.hostt")
        val setNotDeclared = MetadataContractMismatch(
            key, MetadataContractMismatch.Kind.SET_NOT_DECLARED, here, ProfileId("prod"), null, ValueShape.STRING,
        )
        val declaredNotSet = setNotDeclared.copy(kind = MetadataContractMismatch.Kind.DECLARED_NOT_SET)
        assertNotEquals(FindingFingerprint.of(setNotDeclared), FindingFingerprint.of(declaredNotSet))
    }
}
