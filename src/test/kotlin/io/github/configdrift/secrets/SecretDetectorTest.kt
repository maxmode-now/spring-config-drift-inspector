package io.github.configdrift.secrets

import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.parser.KeyNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretDetectorTest {

    private val detector = SecretDetector()

    private fun detect(key: String, value: String?) =
        detector.detect(KeyNormalizer.normalize(key), value)

    private fun ruleId(key: String, value: String?) = detect(key, value)?.rule?.id

    /** Simulates a dotenv key, which bypasses [KeyNormalizer] — see `DotenvKeyNormalizer`. */
    private fun detectVerbatim(key: String, value: String?) =
        detector.detect(NormalizedKey(key), value)

    @Test
    fun `hardcoded password is reported`() {
        assertEquals("password", ruleId("spring.datasource.password", "hunter2"))
    }

    @Test
    fun `correctly externalized secret is not reported`() {
        // Flagging this would train users to ignore the inspection.
        assertNull(detect("spring.datasource.password", "\${DB_PASSWORD}"))
    }

    @Test
    fun `placeholder default still ships the secret`() {
        assertEquals("password", ruleId("spring.datasource.password", "\${DB_PASSWORD:hunter2}"))
    }

    @Test
    fun `masked length describes the committed secret, not the expression`() {
        // `${DB_PASSWORD:changeme}` is 23 characters, but only `changeme` reaches the repository.
        val match = detect("spring.datasource.password", "\${DB_PASSWORD:changeme}")!!
        assertEquals("changeme", match.committedValue)
        assertEquals("•••••••• (len=8)", Masker.mask(match.committedValue))
    }

    @Test
    fun `empty placeholder default is not a secret`() {
        assertNull(detect("spring.datasource.password", "\${DB_PASSWORD:}"))
    }

    @Test
    fun `blank and missing values are not reported`() {
        assertNull(detect("spring.datasource.password", ""))
        assertNull(detect("spring.datasource.password", null))
    }

    @Test
    fun `value rules fire regardless of key name`() {
        assertEquals(
            "private-key",
            ruleId("app.harmless", "-----BEGIN RSA PRIVATE KEY-----\nMII..."),
        )
        assertEquals("aws-access-key-id", ruleId("app.harmless", "AKIAIOSFODNN7EXAMPLE"))
        assertEquals(
            "url-embedded-credentials",
            ruleId("spring.datasource.url", "postgresql://admin:s3cr3t@db.internal:5432/app"),
        )
    }

    @Test
    fun `specific credential formats win attribution over generic key names`() {
        // Both the `api-key` key rule and the AWS value rule match here. Reporting the generic
        // one would mean suppressing it also suppresses real AWS key detection.
        assertEquals("aws-access-key-id", ruleId("app.api-key", "AKIAIOSFODNN7EXAMPLE"))
    }

    @Test
    fun `key names that merely end in key are not credentials`() {
        assertNull(detect("app.cache-key", "orders-v2"))
        assertNull(detect("app.partition-key", "tenant_id"))
    }

    @Test
    fun `dotenv-style underscore-separated keys are recognised too`() {
        // Dotenv keys bypass KeyNormalizer, so the literal underscore survives — these regexes
        // have to recognise it directly rather than relying on KeyNormalizer having already
        // stripped it, as Spring keys can assume.
        assertEquals("password", detectVerbatim("DB_PASSWORD", "hunter2")?.rule?.id)
        assertEquals("api-key", detectVerbatim("API_KEY", "abc123")?.rule?.id)
        assertEquals("token", detectVerbatim("ACCESS_TOKEN", "abc123")?.rule?.id)
        assertEquals("secret", detectVerbatim("CLIENT_SECRET", "abc123")?.rule?.id)
    }

    @Test
    fun `dotenv-style keys that merely end in key are still not credentials`() {
        assertNull(detectVerbatim("CACHE_KEY", "orders-v2"))
        assertNull(detectVerbatim("PARTITION_KEY", "tenant_id"))
    }

    @Test
    fun `ordinary configuration is left alone`() {
        assertNull(detect("server.port", "8080"))
        assertNull(detect("spring.jpa.hibernate.ddl-auto", "validate"))
        assertNull(detect("logging.level.root", "INFO"))
    }

    @Test
    fun `jdbc url with placeholder credentials is not a secret exposure`() {
        assertNull(
            detect(
                "spring.datasource.url",
                "jdbc:postgresql://\${DB_USER}:\${DB_PASSWORD}@localhost/app",
            ),
        )
        assertNull(
            detect(
                "spring.datasource.url",
                "jdbc:postgresql://admin:\${DB_PASSWORD}@localhost/app",
            ),
        )
    }

    @Test
    fun `jdbc url with hardcoded credentials is still reported`() {
        assertEquals(
            "url-embedded-credentials",
            ruleId(
                "spring.datasource.url",
                "postgresql://admin:s3cr3t@db.internal:5432/app",
            ),
        )
    }

    @Test
    fun `jdbc url with placeholder defaults still ships the credentials`() {
        assertEquals(
            "url-embedded-credentials",
            ruleId(
                "spring.datasource.url",
                "postgresql://\${DB_USER:admin}:\${DB_PASSWORD:s3cret}@db.internal/app",
            ),
        )
        assertEquals(
            "url-embedded-credentials",
            ruleId(
                "spring.datasource.url",
                "postgresql://admin:\${DB_PASSWORD:-changeme}@db.internal/app",
            ),
        )
    }
}
