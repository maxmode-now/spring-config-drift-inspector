package io.github.configdrift.metadata

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationPropertyTypesTest {

    @Test
    fun `common value types are known leaves`() {
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName("java.lang.String"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName("java.lang.Integer"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName("java.time.Duration"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName("java.math.BigDecimal"))
    }

    @Test
    fun `collections and maps are treated as leaves too, not recursed into`() {
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName("java.util.List"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName("java.util.Map"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName("java.util.Set"))
    }

    @Test
    fun `an arbitrary custom class name is not a known leaf or container`() {
        assertFalse(ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName("com.example.SmtpProperties"))
    }
}
