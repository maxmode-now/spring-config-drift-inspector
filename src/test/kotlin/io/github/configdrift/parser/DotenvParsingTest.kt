package io.github.configdrift.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DotenvParsingTest {

    @Test
    fun `a plain assignment splits on the first equals sign`() {
        assertEquals(DotenvLine("DB_HOST", "localhost"), DotenvParsing.parseLine("DB_HOST=localhost"))
    }

    @Test
    fun `blank lines and comments are skipped`() {
        assertNull(DotenvParsing.parseLine(""))
        assertNull(DotenvParsing.parseLine("   "))
        assertNull(DotenvParsing.parseLine("# a comment"))
        assertNull(DotenvParsing.parseLine("  # indented comment"))
    }

    @Test
    fun `a line with no equals sign is not an assignment`() {
        assertNull(DotenvParsing.parseLine("not an assignment"))
    }

    @Test
    fun `export prefix is stripped`() {
        assertEquals(DotenvLine("DB_HOST", "localhost"), DotenvParsing.parseLine("export DB_HOST=localhost"))
    }

    @Test
    fun `matching quotes are stripped from the value`() {
        assertEquals(DotenvLine("NAME", "hello world"), DotenvParsing.parseLine("NAME=\"hello world\""))
        assertEquals(DotenvLine("NAME", "hello world"), DotenvParsing.parseLine("NAME='hello world'"))
    }

    @Test
    fun `mismatched quotes are left as plain text`() {
        assertEquals(DotenvLine("NAME", "\"hello"), DotenvParsing.parseLine("NAME=\"hello"))
    }

    @Test
    fun `equals sign inside the value is kept intact`() {
        assertEquals(
            DotenvLine("CONN_STRING", "key=value;other=thing"),
            DotenvParsing.parseLine("CONN_STRING=key=value;other=thing"),
        )
    }

    @Test
    fun `key and value whitespace is trimmed`() {
        assertEquals(DotenvLine("DB_HOST", "localhost"), DotenvParsing.parseLine("  DB_HOST = localhost  "))
    }
}
