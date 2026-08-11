package io.github.configdrift.metadata

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.ContainerUtil
import io.github.configdrift.model.NormalizedKey
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import io.github.configdrift.parser.KeyNormalizer
import io.github.configdrift.spi.BindingContractProvider
import io.github.configdrift.spi.KeyContract

/**
 * Reads `@ConfigurationProperties` classes directly via PSI, so a project's own custom
 * properties classes are covered even when `spring-configuration-metadata.json` is missing or
 * incomplete — annotation-processor generation is easy to leave disabled, especially on smaller
 * projects, and [MetadataContractProvider] alone has no opinion about a class that file never
 * mentions.
 *
 * Java only for now — Kotlin PSI is a large enough separate surface that it belongs in its own
 * provider rather than being bolted onto this one.
 */
class ConfigurationPropertiesContractProvider : BindingContractProvider {

    override val providerId: String = "configuration-properties"

    override fun contractFor(project: Project, key: NormalizedKey): KeyContract? =
        contracts(project)[key]

    override fun declaredKeys(project: Project): Set<NormalizedKey> = contracts(project).keys

    /**
     * PSI has no single modification stamp the way a `VirtualFile` does, so the cache is
     * invalidated by [PsiModificationTracker]'s project-wide counter instead — coarser than
     * [MetadataContractProvider]'s per-file comparison (any PSI edit anywhere invalidates this,
     * not just an edit to a `@ConfigurationProperties` class), but correct, and cheap to redo
     * regardless: [AnnotatedElementsSearch] is index-backed, unlike re-parsing a JSON file's raw
     * bytes, so a coarser cache costs less here than the same coarseness would there.
     */
    private val cache = ContainerUtil.createConcurrentWeakMap<Project, CacheEntry>()

    private data class CacheEntry(val modificationCount: Long, val contracts: Map<NormalizedKey, KeyContract>)

    private fun contracts(project: Project): Map<NormalizedKey, KeyContract> {
        val currentCount = PsiModificationTracker.getInstance(project).modificationCount
        cache[project]?.let { entry -> if (entry.modificationCount == currentCount) return entry.contracts }

        val computed = computeContracts(project)
        cache[project] = CacheEntry(currentCount, computed)
        return computed
    }

    private fun computeContracts(project: Project): Map<NormalizedKey, KeyContract> {
        val annotationClass = JavaPsiFacade.getInstance(project)
            .findClass(CONFIGURATION_PROPERTIES_FQN, GlobalSearchScope.allScope(project))
            ?: return emptyMap() // Spring Boot isn't even a dependency of this project.

        val fileIndex = ProjectFileIndex.getInstance(project)
        val contracts = mutableMapOf<NormalizedKey, KeyContract>()

        // findAll() rather than forEach(): Query.forEach has both a Processor<T> (Boolean-
        // returning) and a Consumer<T> (Unit-returning) overload, and a lambda literal is
        // ambiguous between the two. The discovered class count in any real project is small
        // enough that materializing it up front costs nothing worth avoiding the ambiguity for.
        for (psiClass in AnnotatedElementsSearch.searchPsiClasses(annotationClass, GlobalSearchScope.allScope(project)).findAll()) {
            // Kotlin classes surface here too, through their Java-interop light-class facade —
            // handled by KotlinConfigurationPropertiesContractProvider instead, which reads the
            // real Kotlin PSI rather than the light class's synthetic (and lossy: mangled types,
            // visibility-dependent) field view.
            if (psiClass is KtLightClass) continue
            val virtualFile = psiClass.containingFile?.virtualFile
            if (virtualFile == null || fileIndex.isInTestSourceContent(virtualFile)) continue
            val prefix = prefixOf(psiClass) ?: continue
            collectInto(contracts, prefix, psiClass, visited = mutableSetOf(), depth = 0)
        }
        return contracts
    }

    private fun prefixOf(psiClass: PsiClass): String? {
        val annotation = psiClass.getAnnotation(CONFIGURATION_PROPERTIES_FQN) ?: return null
        val explicit = annotation.findAttributeValue("prefix")?.text
            ?: annotation.findAttributeValue("value")?.text
            ?: return ""
        return explicit.trim('"')
    }

    /**
     * Walks one class's members (fields, or record components for a Java record) into
     * [contracts], recursing into non-leaf member types with `visited`/`depth` guarding against
     * self-referential types and unbounded nesting — the same "good enough, not a full
     * reimplementation" posture this codebase already takes for Spring's `on-profile` boolean
     * expressions.
     *
     * Not modeled: nested properties inside a `List<CustomType>`/`Map<String, CustomType>`
     * element — only the container itself gets a contract, matching how
     * [io.github.configdrift.engine.MetadataContractAnalyzer]'s `isOpenContainer` already expects
     * a Map/Collection contract to look, not per-element ones.
     */
    private fun collectInto(
        contracts: MutableMap<NormalizedKey, KeyContract>,
        prefix: String,
        psiClass: PsiClass,
        visited: MutableSet<PsiClass>,
        depth: Int,
    ) {
        if (depth > MAX_NESTING_DEPTH || !visited.add(psiClass)) return

        val members: List<Pair<String, PsiType>> = if (psiClass.isRecord) {
            psiClass.recordComponents.map { it.name to it.type }
        } else {
            psiClass.allFields
                .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
                .map { it.name to it.type }
        }

        for ((memberName, memberType) in members) {
            val key = KeyNormalizer.normalize(if (prefix.isEmpty()) memberName else "$prefix.$memberName")
            val resolvedClass = (memberType as? PsiClassType)?.resolve()

            if (isLeafType(memberType, resolvedClass)) {
                contracts[key] = KeyContract(
                    key = key,
                    declaredType = memberType.canonicalText,
                    defaultValue = null,
                    sourceProviderId = providerId,
                )
            } else if (resolvedClass != null) {
                collectInto(contracts, key.value, resolvedClass, visited, depth + 1)
            }
        }
    }

    private fun isLeafType(type: PsiType, resolvedClass: PsiClass?): Boolean = when {
        type is PsiPrimitiveType -> true
        resolvedClass == null -> true // Unresolvable (e.g. missing dependency) — don't guess further.
        resolvedClass.isEnum -> true
        else -> {
            val base = (type as? PsiClassType)?.rawType()?.canonicalText ?: type.canonicalText
            ConfigurationPropertyTypes.isKnownLeafOrContainerTypeName(base)
        }
    }

    private companion object {
        const val CONFIGURATION_PROPERTIES_FQN = "org.springframework.boot.context.properties.ConfigurationProperties"
        const val MAX_NESTING_DEPTH = 5
    }
}

/**
 * The type-name allowlist [ConfigurationPropertiesContractProvider] uses to decide whether a
 * member is a leaf (one [KeyContract]) or worth recursing into. Split out as a pure object,
 * rather than living directly in the provider, specifically so this list is unit-testable without
 * constructing PSI — the provider itself isn't (nothing that builds `PsiClass`es is, in this
 * codebase; parser/provider correctness is verified via the `runIde` fixture instead).
 */
object ConfigurationPropertyTypes {

    /**
     * Common value types Spring Boot binds directly from a single scalar, beyond primitives,
     * their boxed wrappers, and enums (which [ConfigurationPropertiesContractProvider] checks
     * before ever consulting this list).
     */
    val LEAF_TYPE_NAMES: Set<String> = setOf(
        "java.lang.String",
        "java.lang.Boolean", "java.lang.Integer", "java.lang.Long", "java.lang.Short",
        "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.lang.Character",
        "java.math.BigDecimal", "java.math.BigInteger",
        "java.time.Duration", "java.time.LocalDate", "java.time.LocalDateTime",
        "java.time.LocalTime", "java.time.Instant",
        "java.net.URI", "java.net.URL",
        "java.io.File", "java.nio.file.Path",
        "java.nio.charset.Charset", "java.util.regex.Pattern",
        "org.springframework.util.unit.DataSize", "org.springframework.util.unit.DataUnit",
    )

    /**
     * A `Collection`/`Map` member is also a leaf as far as this provider is concerned — one
     * [KeyContract] for the container itself, not per-element ones. This matches
     * `MetadataContractAnalyzer`'s existing `isOpenContainer`/`COLLECTION_TYPES`/`MAP_TYPES`
     * handling, which already expects exactly this shape from a JSON-metadata-declared container
     * type and would otherwise disagree with what this provider reports for the same kind of key.
     */
    val CONTAINER_TYPE_NAMES: Set<String> = setOf(
        "java.util.List", "java.util.Set", "java.util.Collection", "java.util.SortedSet",
        "java.util.Map", "java.util.SortedMap", "java.util.Properties",
    )

    fun isKnownLeafOrContainerTypeName(canonicalTypeName: String): Boolean =
        canonicalTypeName in LEAF_TYPE_NAMES || canonicalTypeName in CONTAINER_TYPE_NAMES

    /**
     * The same two concepts, matched by simple name instead of FQN — for
     * [KotlinConfigurationPropertiesContractProvider], which classifies a declared type from its
     * syntax alone (`KtUserType.referencedName`, e.g. `"Smtp"` or `"List"`) rather than a resolved
     * [com.intellij.psi.PsiType], since resolving a Kotlin type reference needs semantic analysis
     * this provider deliberately doesn't perform — see that class's own KDoc for why.
     */
    val KOTLIN_LEAF_SIMPLE_NAMES: Set<String> = setOf(
        "String", "Boolean", "Int", "Long", "Short", "Byte", "Double", "Float", "Char",
        "BigDecimal", "BigInteger",
        "Duration", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
        "URI", "URL",
        "File", "Path",
        "Charset", "Pattern",
        "DataSize", "DataUnit",
    )

    val KOTLIN_CONTAINER_SIMPLE_NAMES: Set<String> = setOf(
        "List", "Set", "Collection", "SortedSet", "MutableList", "MutableSet",
        "Map", "SortedMap", "MutableMap", "Properties", "Array",
    )

    fun isKnownLeafOrContainerSimpleName(simpleName: String): Boolean =
        simpleName in KOTLIN_LEAF_SIMPLE_NAMES || simpleName in KOTLIN_CONTAINER_SIMPLE_NAMES

    /**
     * A Kotlin-spelled base type name (`"Int"`, `"List"`, `"BigDecimal"`) mapped to the
     * Java-spelled name [io.github.configdrift.engine.MetadataContractAnalyzer] actually checks
     * against ([LEAF_TYPE_NAMES]/[CONTAINER_TYPE_NAMES] above, its own private mirror of the same
     * sets). [io.github.configdrift.spi.KeyContract.declaredType]'s documented format is
     * Java-FQN-style regardless of which provider produced it — reporting `"Int"` unmodified would
     * satisfy nothing in [MetadataContractAnalyzer]'s fixed type-name sets, since it matches by
     * exact string, not shape: `TYPE_MISMATCH` would never fire for a Kotlin `Int`/`Long`/... or
     * `Boolean` property, and a Kotlin `List`/`Map` property wouldn't be recognized as an open
     * container, producing false `SET_NOT_DECLARED` on its children. Only names with a real
     * Java-side counterpart in those checked sets are here — `String`, `Duration`, and the other
     * common value types are absent on purpose, matching [MetadataContractAnalyzer]'s own
     * documented behavior of skipping them for *any* provider, Java included.
     */
    val KOTLIN_TO_JAVA_BASE_TYPE_NAMES: Map<String, String> = mapOf(
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
}
