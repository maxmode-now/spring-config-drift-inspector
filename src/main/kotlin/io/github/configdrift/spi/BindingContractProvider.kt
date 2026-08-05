package io.github.configdrift.spi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import io.github.configdrift.model.NormalizedKey

/**
 * The one deliberate extension seam in the MVP.
 *
 * The MVP ships exactly one implementation source — `spring-configuration-metadata.json` — and
 * intentionally does **not** analyze `@ConfigurationProperties` classes, reproduce the Spring
 * Binder, or handle custom converters, SpEL, Vault, or Config Server. Should user demand for
 * real binding diagnostics show up, a PSI-based provider plugs in here instead of the engine
 * being reshaped around it.
 *
 * Implementations must be cheap and side-effect free: [contractFor] is called once per key per
 * analysis run, off the EDT.
 */
interface BindingContractProvider {

    /** Stable id used in report output and in user-facing rule suppression. */
    val providerId: String

    /**
     * The declared contract for [key], or null if this provider knows nothing about it.
     * Returning null must mean "no opinion", never "not declared" — the engine cannot tell
     * the difference otherwise and would emit false SET_NOT_DECLARED findings.
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
    }
}

/**
 * What a provider claims about a key. Deliberately thin: a fully qualified type name and a
 * deprecation flag, not a resolved type model. Anything richer belongs behind this interface,
 * not in it.
 */
data class KeyContract(
    val key: NormalizedKey,
    /** e.g. `java.lang.Integer`, `java.util.List<java.lang.String>`. */
    val declaredType: String?,
    val defaultValue: String?,
    val deprecated: Boolean = false,
    val sourceProviderId: String,
)
