package io.github.configdrift.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Regression test for the bug this map exists to fix: a Kotlin-spelled declaredType
     * (`"Int"`, `"List"`) silently never matches `MetadataContractAnalyzer`'s Java-spelled,
     * exact-string type-name sets, so TYPE_MISMATCH would never fire for a Kotlin numeric/boolean
     * property and a Kotlin List/Map property would never be recognized as an open container.
     * These expected values intentionally mirror `MetadataContractAnalyzer`'s private
     * INTEGER_TYPES/DECIMAL_TYPES/BOOLEAN_TYPES/COLLECTION_TYPES/MAP_TYPES sets exactly — there is
     * no way to reference those directly from here, so this is the check that would catch the two
     * ever drifting apart.
     */
    @Test
    fun `every mapped kotlin type name has the exact java spelling the analyzer checks for`() {
        val expected = mapOf(
            "Boolean" to "java.lang.Boolean",
            "Int" to "java.lang.Integer",
            "Long" to "java.lang.Long",
            "Short" to "java.lang.Short",
            "Byte" to "java.lang.Byte",
            "BigInteger" to "java.math.BigInteger",
            "Double" to "java.lang.Double",
            "Float" to "java.lang.Float",
            "BigDecimal" to "java.math.BigDecimal",
            "List" to "java.util.List",
            "MutableList" to "java.util.List",
            "Set" to "java.util.Set",
            "MutableSet" to "java.util.Set",
            "SortedSet" to "java.util.SortedSet",
            "Collection" to "java.util.Collection",
            "Array" to "java.util.List",
            "Map" to "java.util.Map",
            "MutableMap" to "java.util.Map",
            "SortedMap" to "java.util.SortedMap",
            "Properties" to "java.util.Properties",
        )
        assertEquals(expected, ConfigurationPropertyTypes.KOTLIN_TO_JAVA_BASE_TYPE_NAMES)
    }

    @Test
    fun `every mapped name is itself a known leaf or container simple name`() {
        // Otherwise the translation would run for a type this provider never actually classifies
        // as leaf/container in the first place — dead entries, or worse, a name missing from one
        // list but present in the other.
        for (kotlinName in ConfigurationPropertyTypes.KOTLIN_TO_JAVA_BASE_TYPE_NAMES.keys) {
            assertTrue(
                ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName(kotlinName),
                "'$kotlinName' is mapped but not recognised as a known leaf/container name",
            )
        }
    }

    @Test
    fun `common value types with no analyzer-checked equivalent are deliberately unmapped`() {
        // String, Duration, and friends are skipped by MetadataContractAnalyzer for every
        // provider, Java included — mapping them here would do nothing but add noise.
        assertFalse("String" in ConfigurationPropertyTypes.KOTLIN_TO_JAVA_BASE_TYPE_NAMES)
        assertFalse("Duration" in ConfigurationPropertyTypes.KOTLIN_TO_JAVA_BASE_TYPE_NAMES)
    }
}
