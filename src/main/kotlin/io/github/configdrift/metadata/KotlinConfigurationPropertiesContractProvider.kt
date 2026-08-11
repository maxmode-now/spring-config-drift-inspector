package io.github.configdrift.metadata

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.parser.KeyNormalizer
import io.github.configdrift.spi.BindingContractProvider
import io.github.configdrift.spi.KeyContract
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reads Kotlin `@ConfigurationProperties` classes — [ConfigurationPropertiesContractProvider]'s
 * Java-only counterpart, kept as a separate provider because their PSI member models don't share
 * much (`PsiField` vs. `KtParameter`/`KtProperty`).
 *
 * **Syntactic only — no semantic type resolution, deliberately.** A declared type like `Smtp` is
 * read as the literal text `KtUserType.referencedName`, never resolved to an actual class through
 * Kotlin's analysis machinery (the Analysis API, or the older `BindingContext`). That machinery is
 * one of the most version-fragile parts of the Kotlin plugin right now, mid-migration from the K1
 * to the K2 compiler frontend across the IDE versions this plugin targets — staying syntactic
 * keeps this provider on the same PSI tree shape across both.
 *
 * The direct consequence: recursing into a nested custom type only works when that type is
 * **declared in the same file** as the `@ConfigurationProperties` class. A type imported from
 * elsewhere can't be told apart from an unrecognized library type without resolving it, so both
 * are treated the same way this provider treats anything else it can't classify — as a leaf, not
 * guessed at further.
 */
class KotlinConfigurationPropertiesContractProvider : BindingContractProvider {

    override val providerId: String = "configuration-properties-kotlin"

    override fun contractFor(project: Project, key: NormalizedKey): KeyContract? =
        contracts(project)[key]

    override fun declaredKeys(project: Project): Set<NormalizedKey> = contracts(project).keys

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

        // AnnotatedElementsSearch returns Java-interop light classes for Kotlin results too —
        // bridge back to the real Kotlin PSI via KtLightClass.kotlinOrigin. A plain PsiClass here
        // means it's a Java class, handled by ConfigurationPropertiesContractProvider instead.
        for (psiClass in AnnotatedElementsSearch.searchPsiClasses(annotationClass, GlobalSearchScope.allScope(project)).findAll()) {
            val ktClass = (psiClass as? KtLightClass)?.kotlinOrigin as? KtClass ?: continue
            val virtualFile = ktClass.containingFile.virtualFile
            if (virtualFile == null || fileIndex.isInTestSourceContent(virtualFile)) continue
            val prefix = prefixOf(ktClass) ?: continue
            collectInto(contracts, prefix, ktClass, visited = mutableSetOf(), depth = 0)
        }
        return contracts
    }

    private fun prefixOf(ktClass: KtClass): String? {
        val annotation = ktClass.annotationEntries
            .firstOrNull { it.shortName?.asString() == "ConfigurationProperties" }
            ?: return null
        val explicit = argumentText(annotation, "prefix") ?: argumentText(annotation, "value")
        return explicit?.trim('"') ?: ""
    }

    /** Named argument first (`prefix = "app.mail"`); falls back to a single positional argument. */
    private fun argumentText(annotation: KtAnnotationEntry, name: String): String? {
        val args = annotation.valueArguments
        val arg = args.firstOrNull { it.getArgumentName()?.asName?.asString() == name }
            ?: args.singleOrNull()
            ?: return null
        return arg.getArgumentExpression()?.text
    }

    /**
     * Walks one class's primary-constructor `val`/`var` parameters and body-declared properties
     * into [contracts]. `visited`/`depth` guard the same way
     * [ConfigurationPropertiesContractProvider.collectInto] does, for the same reason
     * (self-referential types, unbounded nesting).
     *
     * A body property with no explicit type annotation (`val x = "foo"`, relying on inference) is
     * skipped — there is no [KtTypeReference] to read without resolving the initializer
     * expression, which this provider doesn't do.
     */
    private fun collectInto(
        contracts: MutableMap<NormalizedKey, KeyContract>,
        prefix: String,
        ktClass: KtClass,
        visited: MutableSet<KtClass>,
        depth: Int,
    ) {
        if (depth > MAX_NESTING_DEPTH || !visited.add(ktClass)) return

        val constructorMembers = ktClass.primaryConstructorParameters
            .filter { it.hasValOrVar() }
            .mapNotNull { param -> param.name?.let { it to param.typeReference } }
        val bodyMembers = ktClass.getProperties()
            .mapNotNull { prop -> prop.name?.let { it to prop.typeReference } }

        for ((memberName, typeReference) in constructorMembers + bodyMembers) {
            if (typeReference == null) continue
            val key = KeyNormalizer.normalize(if (prefix.isEmpty()) memberName else "$prefix.$memberName")
            val userType = unwrapToUserType(typeReference) ?: run {
                // Not a simple named type (function type, etc.) — record as an unclassified leaf
                // rather than silently dropping the property.
                contracts[key] = leafContract(key, typeReference.text)
                continue
            }
            val simpleName = userType.referencedName

            if (simpleName == null || ConfigurationPropertyTypes.isKnownLeafOrContainerSimpleName(simpleName)) {
                contracts[key] = leafContract(key, typeReference.text)
                continue
            }

            val nestedClass = findClassInSameFile(ktClass, simpleName)
            if (nestedClass != null) {
                collectInto(contracts, key.value, nestedClass, visited, depth + 1)
            } else {
                contracts[key] = leafContract(key, typeReference.text)
            }
        }
    }

    private fun leafContract(key: NormalizedKey, declaredTypeText: String) = KeyContract(
        key = key,
        declaredType = declaredTypeText,
        defaultValue = null,
        sourceProviderId = providerId,
    )

    /** Unwraps a `Type?` nullable annotation to reach the underlying [KtUserType], if any. */
    private fun unwrapToUserType(typeReference: KtTypeReference): KtUserType? {
        var element = typeReference.typeElement
        while (element is KtNullableType) element = element.innerType
        return element as? KtUserType
    }

    private fun findClassInSameFile(from: KtClass, simpleName: String): KtClass? {
        val file = from.containingFile as? KtFile ?: return null
        return PsiTreeUtil.findChildrenOfType(file, KtClass::class.java)
            .firstOrNull { it.name == simpleName }
    }

    private companion object {
        const val CONFIGURATION_PROPERTIES_FQN = "org.springframework.boot.context.properties.ConfigurationProperties"
        const val MAX_NESTING_DEPTH = 5
    }
}
