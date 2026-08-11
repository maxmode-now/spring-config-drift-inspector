package io.github.configdrift.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KeyNormalizerTest {

    @Test
    fun `relaxed binding spellings collapse to one key`() {
        val expected = KeyNormalizer.normalize("spring.datasource.driver-class-name")
        assertEquals(expected, KeyNormalizer.normalize("spring.datasource.driverClassName"))
        assertEquals(expected, KeyNormalizer.normalize("spring.datasource.DRIVER_CLASS_NAME"))
        assertEquals(expected, KeyNormalizer.normalize("SPRING.DATASOURCE.driverclassname"))
    }

    @Test
    fun `list indices stay distinguishable`() {
        assertNotEquals(
            KeyNormalizer.normalize("my.hosts[0]"),
            KeyNormalizer.normalize("my.hosts[1]"),
        )
        assertEquals("my.hosts[0]", KeyNormalizer.normalize("my.hosts[0]").value)
    }

    @Test
    fun `nested yaml paths join into dotted keys`() {
        assertEquals(
            KeyNormalizer.normalize("spring.jpa.hibernate.ddl-auto"),
            KeyNormalizer.fromPath(listOf("spring", "jpa", "hibernate", "ddlAuto")),
        )
    }
}
