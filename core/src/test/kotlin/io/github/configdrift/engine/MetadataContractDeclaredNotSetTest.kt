package io.github.configdrift.engine

import io.github.configdrift.model.NormalizedKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataContractDeclaredNotSetTest {

    private fun keys(vararg names: String): Set<NormalizedKey> =
        names.mapTo(mutableSetOf()) { NormalizedKey(it) }

    @Test
    fun `a declared key that appears itself is satisfied`() {
        assertTrue(
            MetadataContractAnalyzer.isDeclaredKeySatisfied(
                NormalizedKey("app.server.host"),
                keys("app.server.host"),
            ),
        )
    }

    @Test
    fun `a declared key with only nested children set is still satisfied`() {
        // Cross-file Kotlin nested type / Map-style usage: parent never appears as a scalar.
        assertTrue(
            MetadataContractAnalyzer.isDeclaredKeySatisfied(
                NormalizedKey("app.server.database"),
                keys("app.server.database.url", "app.server.database.poolsize"),
            ),
        )
    }

    @Test
    fun `a declared key with only indexed children set is still satisfied`() {
        assertTrue(
            MetadataContractAnalyzer.isDeclaredKeySatisfied(
                NormalizedKey("app.items"),
                keys("app.items[0].name"),
            ),
        )
    }

    @Test
    fun `an unrelated sibling key does not satisfy a declaration`() {
        assertFalse(
            MetadataContractAnalyzer.isDeclaredKeySatisfied(
                NormalizedKey("app.server.database"),
                keys("app.server.host"),
            ),
        )
        assertFalse(
            MetadataContractAnalyzer.isDeclaredKeySatisfied(
                NormalizedKey("app.cache"),
                keys("app.cachesize"),
            ),
        )
    }

    @Test
    fun `a truly unset leaf stays unsatisfied`() {
        assertFalse(
            MetadataContractAnalyzer.isDeclaredKeySatisfied(
                NormalizedKey("app.unused-setting"),
                keys("app.server.host"),
            ),
        )
    }
}
