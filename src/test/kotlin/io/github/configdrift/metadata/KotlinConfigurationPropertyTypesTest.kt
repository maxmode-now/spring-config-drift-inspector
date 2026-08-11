package io.github.configdrift.metadata

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinConfigurationPropertyTypesTest {

    @Test
    fun `common kotlin value types are known leaves`() {
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName("String"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName("Int"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName("Duration"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName("BigDecimal"))
    }

    @Test
    fun `collections and maps are treated as leaves too, not recursed into`() {
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName("List"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName("MutableMap"))
        assertTrue(ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName("Set"))
    }

    @Test
    fun `an arbitrary custom class simple name is not a known leaf or container`() {
        assertFalse(ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName("Smtp"))
    }
}
