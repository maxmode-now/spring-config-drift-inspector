package io.github.configdrift.spi

import io.github.configdrift.model.NormalizedKey

/**
 * What a provider claims about a key. Deliberately thin: a fully qualified type name and a
 * deprecation flag, not a resolved type model.
 */
data class KeyContract(
    val key: NormalizedKey,
    /** e.g. `java.lang.Integer`, `java.util.List<java.lang.String>`. */
    val declaredType: String?,
    val defaultValue: String?,
    val deprecated: Boolean = false,
    val sourceProviderId: String,
)

/**
 * Pre-resolved binding contracts for one analysis run. Analyzers never call IDE APIs; callers
 * (plugin EP adapters, CLI file loaders) build this map before constructing [io.github.configdrift.engine.AnalysisContext].
 */
class ContractCatalog(
    private val contracts: Map<NormalizedKey, KeyContract>,
) {
    val declaredKeys: Set<NormalizedKey> get() = contracts.keys

    val hasContracts: Boolean get() = contracts.isNotEmpty()

    fun contractFor(key: NormalizedKey): KeyContract? = contracts[key]

    companion object {
        val EMPTY = ContractCatalog(emptyMap())

        fun of(contracts: Iterable<KeyContract>): ContractCatalog =
            ContractCatalog(contracts.associateBy { it.key })

        /** Later sources win on key collision (matches Spring Boot merge for additions vs generated). */
        fun merge(vararg catalogs: ContractCatalog): ContractCatalog {
            val merged = linkedMapOf<NormalizedKey, KeyContract>()
            for (catalog in catalogs) {
                merged.putAll(catalog.contracts)
            }
            return ContractCatalog(merged)
        }
    }
}
