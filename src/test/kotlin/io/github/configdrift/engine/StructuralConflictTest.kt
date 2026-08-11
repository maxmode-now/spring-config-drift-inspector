package io.github.configdrift.engine

import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuralConflictTest {

    private fun keys(vararg names: String): Set<NormalizedKey> =
        names.mapTo(mutableSetOf()) { NormalizedKey(it) }

    @Test
    fun `a scalar in one profile and an object in another is a conflict`() {
        val conflicts = StructuralConflict.detect(
            mapOf(
                ProfileId("dev") to keys("app.cache"),
                ProfileId("prod") to keys("app.cache.host", "app.cache.port"),
            ),
        )
        assertEquals(keys("app.cache"), conflicts)
    }

    @Test
    fun `the same shape everywhere is not a conflict`() {
        val conflicts = StructuralConflict.detect(
            mapOf(
                ProfileId("dev") to keys("app.cache.host"),
                ProfileId("prod") to keys("app.cache.host"),
            ),
        )
        assertEquals(emptySet(), conflicts)
    }

    @Test
    fun `one profile holding both leaf and children is not cross-environment drift`() {
        // Odd within a single file, but nothing differs *between* environments.
        val conflicts = StructuralConflict.detect(
            mapOf(ProfileId("dev") to keys("app.cache", "app.cache.host")),
        )
        assertEquals(emptySet(), conflicts)
    }

    @Test
    fun `a merely missing key is not a structural conflict`() {
        val conflicts = StructuralConflict.detect(
            mapOf(
                ProfileId("dev") to keys("app.cache"),
                ProfileId("prod") to keys("server.port"),
            ),
        )
        assertEquals(emptySet(), conflicts)
    }

    @Test
    fun `children of a conflicting key are covered so they are not reported twice`() {
        val conflicts = keys("app.cache")
        assertTrue(StructuralConflict.isCoveredBy(NormalizedKey("app.cache"), conflicts))
        assertTrue(StructuralConflict.isCoveredBy(NormalizedKey("app.cache.host"), conflicts))
        assertFalse(StructuralConflict.isCoveredBy(NormalizedKey("app.cachesize"), conflicts))
        assertFalse(StructuralConflict.isCoveredBy(NormalizedKey("app.other"), conflicts))
    }

    @Test
    fun `a scalar in one profile and a list of objects in another is a conflict`() {
        // YamlConfigParser joins indexes as app.items[0].name — no '.' before '['.
        val conflicts = StructuralConflict.detect(
            mapOf(
                ProfileId("dev") to keys("app.items"),
                ProfileId("prod") to keys("app.items[0].name", "app.items[0].weight"),
            ),
        )
        assertEquals(keys("app.items"), conflicts)
    }

    @Test
    fun `indexed children of a conflicting key are covered so they are not reported twice`() {
        val conflicts = keys("app.items")
        assertTrue(StructuralConflict.isCoveredBy(NormalizedKey("app.items[0].name"), conflicts))
        assertTrue(StructuralConflict.isCoveredBy(NormalizedKey("app.items[1].name"), conflicts))
        assertFalse(StructuralConflict.isCoveredBy(NormalizedKey("app.itemsExtra[0]"), conflicts))
    }

    @Test
    fun `isChildOf requires a property boundary after the parent`() {
        assertTrue(StructuralConflict.isChildOf("app.cache", "app.cache.host"))
        assertTrue(StructuralConflict.isChildOf("app.items", "app.items[0].name"))
        assertFalse(StructuralConflict.isChildOf("app.cache", "app.cachesize"))
        assertFalse(StructuralConflict.isChildOf("app.items", "app.itemsExtra[0]"))
    }
}
