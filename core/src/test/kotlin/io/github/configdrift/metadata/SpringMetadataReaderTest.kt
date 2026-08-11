package io.github.configdrift.metadata

import io.github.configdrift.parser.KeyNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpringMetadataReaderTest {

    @Test
    fun `reads properties from a metadata document`() {
        val json = """
            {
              "groups": [
                { "name": "app.mail", "type": "com.example.MailProperties" }
              ],
              "properties": [
                {
                  "name": "app.mail.host",
                  "type": "java.lang.String",
                  "defaultValue": "localhost"
                },
                {
                  "name": "app.mail.port",
                  "type": "java.lang.Integer",
                  "defaultValue": 25
                },
                {
                  "name": "app.mail.legacy-flag",
                  "type": "java.lang.Boolean",
                  "deprecation": { "level": "warning" }
                }
              ]
            }
        """.trimIndent()

        val contracts = SpringMetadataReader.read(json, "test").associateBy { it.key }

        assertEquals(3, contracts.size)
        assertEquals(
            "java.lang.Integer",
            contracts[KeyNormalizer.normalize("app.mail.port")]?.declaredType,
        )
        assertTrue(contracts[KeyNormalizer.normalize("app.mail.legacyFlag")]!!.deprecated)
    }

    @Test
    fun `escapes and nesting round-trip through the reader`() {
        val parsed = MiniJson.parse("""{"a":"line\nbreak A","b":[1,-2.5e3,true,null]}""")
        val map = parsed as Map<*, *>
        assertEquals("line\nbreak A", map["a"])
        assertEquals(listOf(1.0, -2500.0, true, null), map["b"])
    }

    @Test
    fun `a document without a properties array yields nothing`() {
        assertEquals(emptyList(), SpringMetadataReader.read("""{"groups":[]}""", "test"))
    }
}
