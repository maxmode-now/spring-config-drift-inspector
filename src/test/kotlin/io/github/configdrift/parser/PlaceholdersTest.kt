package io.github.configdrift.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceholdersTest {

    @Test
    fun `parses name and default`() {
        val refs = Placeholders.parse("jdbc:postgresql://\${DB_HOST:localhost}:5432/app")
        assertEquals(1, refs.size)
        assertEquals("DB_HOST", refs[0].name)
        assertEquals("localhost", refs[0].defaultValue)
    }

    @Test
    fun `no default is distinguished from an empty default`() {
        assertNull(Placeholders.parse("\${DB_HOST}").single().defaultValue)
        assertEquals("", Placeholders.parse("\${DB_HOST:}").single().defaultValue)
    }

    @Test
    fun `nested placeholders are not truncated at the first brace`() {
        val ref = Placeholders.parse("\${outer:\${inner}}").single()
        assertEquals("outer", ref.name)
        assertEquals("\${inner}", ref.defaultValue)
        assertEquals("\${outer:\${inner}}", ref.raw)
    }

    @Test
    fun `multiple references in one value`() {
        val refs = Placeholders.parse("\${A}-\${B}")
        assertEquals(listOf("A", "B"), refs.map { it.name })
    }

    @Test
    fun `fully externalized means exactly one bare placeholder`() {
        assertTrue(Placeholders.isFullyExternalized("\${DB_PASSWORD}"))
        assertFalse(Placeholders.isFullyExternalized("\${DB_PASSWORD:hunter2}"))
        assertFalse(Placeholders.isFullyExternalized("prefix-\${DB_PASSWORD}"))
        assertFalse(Placeholders.isFullyExternalized("literal"))
    }

    @Test
    fun `bash-style colon-dash default is recognised`() {
        val ref = Placeholders.parse("\${DB_HOST:-localhost}").single()
        assertEquals("DB_HOST", ref.name)
        assertEquals("localhost", ref.defaultValue)
    }

    @Test
    fun `a spring kebab-case property name with no default is not misread as one`() {
        // The whole point of requiring the colon: a bare `-` inside a name must never be treated
        // as a default-value separator, since Spring placeholders can legitimately be kebab-case.
        val ref = Placeholders.parse("\${server.error.include-stacktrace}").single()
        assertEquals("server.error.include-stacktrace", ref.name)
        assertNull(ref.defaultValue)
    }

    @Test
    fun `materializeCommitted keeps literals and substitutes non-blank defaults`() {
        assertEquals("literal", Placeholders.materializeCommitted("literal"))
        assertEquals("hunter2", Placeholders.materializeCommitted("\${DB_PASSWORD:hunter2}"))
        assertEquals(
            "postgresql://admin:s3cret@host/db",
            Placeholders.materializeCommitted(
                "postgresql://\${U:admin}:\${P:s3cret}@host/db",
            ),
        )
        assertEquals(
            "localhost",
            Placeholders.materializeCommitted("\${DB_HOST:-localhost}"),
        )
    }

    @Test
    fun `materializeCommitted drops bare placeholders so nothing committed remains`() {
        assertNull(Placeholders.materializeCommitted("\${DB_PASSWORD}"))
        assertNull(Placeholders.materializeCommitted("\${DB_PASSWORD:}"))
        assertEquals(
            "postgresql://:@host/db",
            Placeholders.materializeCommitted(
                "postgresql://\${DB_USER}:\${DB_PASSWORD}@host/db",
            ),
        )
    }
}
