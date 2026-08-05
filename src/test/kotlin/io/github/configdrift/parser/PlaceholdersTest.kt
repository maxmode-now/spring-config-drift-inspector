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
}
