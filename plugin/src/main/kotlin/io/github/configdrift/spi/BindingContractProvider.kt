package io.github.configdrift.spi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import io.github.configdrift.model.NormalizedKey

/**
 * IDE extension seam for binding contracts.
 *
 * Implementations must be cheap and side-effect free: [contractFor] is called once per key per
 * analysis run (or once when materialising a [ContractCatalog]), off the EDT.
 */
interface BindingContractProvider {

    /** Stable id used in report output and in user-facing rule suppression. */
    val providerId: String

    /**
     * The declared contract for [key], or null if this provider knows nothing about it.
     * Returning null must mean "no opinion", never "not declared".
     */
    fun contractFor(project: Project, key: NormalizedKey): KeyContract?

    /**
     * Every key this provider knows to be declared. Used for the
     * `declared but never set` direction of the contract check.
     */
    fun declaredKeys(project: Project): Set<NormalizedKey>

    companion object {
        val EP_NAME: ExtensionPointName<BindingContractProvider> =
            ExtensionPointName.create("io.github.configdrift.bindingContractProvider")

        /** Materialise every registered provider into a pure [ContractCatalog] for the engine. */
        fun loadCatalog(project: Project): ContractCatalog {
            val merged = linkedMapOf<NormalizedKey, KeyContract>()
            for (provider in EP_NAME.extensionList) {
                for (key in provider.declaredKeys(project)) {
                    val contract = provider.contractFor(project, key) ?: continue
                    merged[key] = contract
                }
            }
            return ContractCatalog(merged)
        }
    }
}
