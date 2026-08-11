package io.github.configdrift.engine

import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigValue
import io.github.configdrift.model.Finding
import io.github.configdrift.model.MetadataContractMismatch
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ValueShape
import io.github.configdrift.parser.Placeholders
import io.github.configdrift.spi.KeyContract

/**
 * Compares the config files against declared binding contracts (from
 * `spring-configuration-metadata.json` and, in the IDE, `@ConfigurationProperties` providers).
 *
 * Stays completely silent when the project has no metadata, because "undeclared" is meaningless
 * without a declaration source.
 *
 * The container-prefix rule is what makes the `set but not declared` direction usable: a property
 * declared as `Map<String, String>` legitimately accepts arbitrary child keys, so `my.map.anything`
 * must not be reported merely because only `my.map` appears in the metadata.
 */
class MetadataContractAnalyzer : DriftAnalyzer {

    override val id: String = "metadata-contract"

    override fun analyze(context: AnalysisContext): List<Finding> {
        if (!context.hasContracts) return emptyList()

        val findings = mutableListOf<Finding>()
        val declared = context.declaredKeys
        val containerPrefixes = declared.filter { key ->
            context.contractFor(key)?.declaredType?.let(::isOpenContainer) == true
        }
        val declaredNamespaces = declared.mapTo(mutableSetOf()) { namespaceOf(it) }

        for (profileSnapshot in context.snapshot.profiles) {
            for (entry in profileSnapshot.entries) {
                // Metadata declares Spring properties, so only Spring entries can contradict it.
                // Without this the namespace test below misfires on a docker-compose service that
                // happens to be named after a declared Spring namespace: a service called `app`
                // yields keys like `app.DB_HOST`, whose namespace *is* declared, so every one of
                // its environment variables would be reported as an undeclared Spring property.
                if (entry.domain != ConfigDomain.SPRING) continue

                val bare = stripIndices(entry.key)
                val contract = context.contractFor(bare)

                if (contract == null) {
                    if (bare !in declared &&
                        namespaceOf(bare) in declaredNamespaces &&
                        containerPrefixes.none { bare.isChildOf(it) }
                    ) {
                        findings += MetadataContractMismatch(
                            key = entry.key,
                            kind = MetadataContractMismatch.Kind.SET_NOT_DECLARED,
                            location = entry.location,
                            profile = profileSnapshot.profile,
                            declaredType = null,
                            actualShape = entry.shape,
                        )
                    }
                    continue
                }

                typeMismatch(contract, entry.value, isIndexed = bare != entry.key)?.let { shape ->
                    findings += MetadataContractMismatch(
                        key = entry.key,
                        kind = MetadataContractMismatch.Kind.TYPE_MISMATCH,
                        location = entry.location,
                        profile = profileSnapshot.profile,
                        declaredType = contract.declaredType,
                        actualShape = shape,
                    )
                }
            }
        }

        // Spring entries only, for the same reason: a `.env` variable that coincidentally matches
        // a declared property name does not mean that Spring property is actually set.
        val setKeys = context.snapshot.profiles.asSequence()
            .flatMap { it.entries.asSequence() }
            .filter { it.domain == ConfigDomain.SPRING }
            .mapTo(mutableSetOf()) { stripIndices(it.key) }
        for (key in declared) {
            if (isDeclaredKeySatisfied(key, setKeys)) continue
            findings += MetadataContractMismatch(
                key = key,
                kind = MetadataContractMismatch.Kind.DECLARED_NOT_SET,
                location = null,
                profile = null,
                declaredType = context.contractFor(key)?.declaredType,
                actualShape = null,
            )
        }
        return findings
    }

    /**
     * Only reports a mismatch when the declared type admits a small, closed set of shapes.
     * Unknown or open types (String, enums, Duration, custom classes) are skipped rather than
     * guessed at — that guessing is where a "simple type check" turns into Binder emulation.
     */
    private fun typeMismatch(
        contract: KeyContract,
        value: ConfigValue,
        isIndexed: Boolean,
    ): ValueShape? {
        val declaredType = contract.declaredType ?: return null

        // `app.hosts[0]` is an *element* of the declared list, so comparing it against
        // `List<String>` reported every element as a type error. Compare against the element
        // type instead, and stay silent when the declaration carries no generic information.
        val effectiveType =
            if (isIndexed) elementTypeOf(declaredType) ?: return null else declaredType

        val acceptable = acceptableShapes(effectiveType) ?: return null

        // A placeholder's real value is unknown at analysis time, so its shape proves nothing.
        val text = (value as? ConfigValue.Plain)?.text
        if (text != null && Placeholders.parse(text).isNotEmpty()) return null
        if (value.shape == ValueShape.NULL) return null

        return if (value.shape in acceptable) null else value.shape
    }

    private fun acceptableShapes(declaredType: String): Set<ValueShape>? {
        val base = declaredType.substringBefore('<')
        return when {
            base in INTEGER_TYPES -> setOf(ValueShape.INTEGER)
            base in DECIMAL_TYPES -> setOf(ValueShape.INTEGER, ValueShape.DECIMAL)
            base in BOOLEAN_TYPES -> setOf(ValueShape.BOOLEAN)
            isCollection(base) -> setOf(ValueShape.LIST)
            isMap(base) -> setOf(ValueShape.MAP)
            else -> null
        }
    }

    private fun isOpenContainer(declaredType: String): Boolean {
        val base = declaredType.substringBefore('<')
        return isMap(base) || isCollection(base)
    }

    private fun isMap(base: String) = base in MAP_TYPES
    private fun isCollection(base: String) = base in COLLECTION_TYPES || base.endsWith("[]")

    /** `List<String>` -> `String`, `Map<String, Integer>` -> `Integer`, `String[]` -> `String`. */
    private fun elementTypeOf(declaredType: String): String? = when {
        declaredType.endsWith("[]") -> declaredType.removeSuffix("[]")
        '<' in declaredType ->
            declaredType.substringAfter('<').substringBeforeLast('>')
                .split(',').last().trim().takeIf { it.isNotEmpty() }
        else -> null
    }

    /**
     * The root segment of a key. Only keys whose namespace appears in the project's own metadata
     * are eligible for SET_NOT_DECLARED — otherwise every framework property (`spring.*`,
     * `server.*`) is reported, because those are declared in dependency jars the MVP does not
     * read. That flood was 18 of 22 warnings on a five-file sample project.
     */
    private fun namespaceOf(key: NormalizedKey): String = key.value.substringBefore('.')

    private fun stripIndices(key: NormalizedKey): NormalizedKey =
        NormalizedKey(key.value.replace(INDEX, ""))

    private fun NormalizedKey.isChildOf(prefix: NormalizedKey): Boolean =
        value.startsWith(prefix.value + ".")

    companion object {
        /**
         * True when [declared] itself appears in config, or any nested / indexed child of it does.
         *
         * Nested YAML for a Map (or an unresolved Kotlin nested type that used to be registered
         * as a scalar leaf) only produces child keys — without this check the parent would get a
         * spurious DECLARED_NOT_SET even though the binding is clearly in use.
         */
        fun isDeclaredKeySatisfied(declared: NormalizedKey, setKeys: Set<NormalizedKey>): Boolean =
            declared in setKeys ||
                setKeys.any { StructuralConflict.isChildOf(declared.value, it.value) }

        private val INDEX = Regex("""\[[^]]*]""")

        private val INTEGER_TYPES = setOf(
            "int", "long", "short", "byte",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.math.BigInteger",
        )
        private val DECIMAL_TYPES = setOf(
            "double", "float",
            "java.lang.Double", "java.lang.Float", "java.math.BigDecimal",
        )
        private val BOOLEAN_TYPES = setOf("boolean", "java.lang.Boolean")
        private val COLLECTION_TYPES = setOf(
            "java.util.List", "java.util.Set", "java.util.Collection", "java.util.SortedSet",
        )
        private val MAP_TYPES = setOf(
            "java.util.Map", "java.util.SortedMap", "java.util.Properties",
        )
    }
}
