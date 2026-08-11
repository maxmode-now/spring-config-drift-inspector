package io.github.configdrift.engine

import io.github.configdrift.model.ConfigValue
import io.github.configdrift.model.Finding
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.UnresolvedPlaceholder
import io.github.configdrift.parser.KeyNormalizer
import io.github.configdrift.parser.Placeholders

/**
 * Flags `${...}` references that nothing in the project appears to supply.
 *
 * This is a heuristic and its findings are always [io.github.configdrift.model.Severity.WARNING],
 * never ERROR. The plugin cannot see the deployment environment: a reference with no default and
 * no in-project definition is *usually* a mistake, but it is also exactly how a correctly
 * externalized secret looks. Claiming it is unresolved would be asserting something the plugin
 * has no way to know.
 *
 * A reference is treated as satisfied when it has a default, or when a config key matches it in
 * either dotted or environment-variable spelling — `${DB_HOST}` is considered supplied by a
 * `db.host` key, since Spring's relaxed binding makes those interchangeable at lookup time.
 */
class PlaceholderAnalyzer : DriftAnalyzer {

    override val id: String = "unresolved-placeholder"

    override fun analyze(context: AnalysisContext): List<Finding> {
        val knownKeys = context.snapshot.allKeys
        val findings = mutableListOf<Finding>()

        for (profileSnapshot in context.snapshot.profiles) {
            for (entry in profileSnapshot.entries) {
                // Redacted values are hardcoded secrets by definition, so they contain no
                // placeholder worth resolving; the plaintext is gone either way.
                val text = (entry.value as? ConfigValue.Plain)?.text ?: continue

                for (reference in Placeholders.parse(text)) {
                    if (reference.hasDefault) continue
                    if (isSupplied(reference.name, knownKeys, context.declaredKeys)) continue

                    findings += UnresolvedPlaceholder(
                        key = entry.key,
                        profile = profileSnapshot.profile,
                        location = entry.location,
                        placeholder = reference.name,
                        hasDefault = false,
                        kind = kindOf(reference.name),
                    )
                }
            }
        }
        return findings
    }

    /**
     * `UPPER_SNAKE_CASE` is the universal spelling for a deployment environment variable, and
     * whether one is set at deploy time is unknowable from the repository. A dotted lower-case
     * name, by contrast, is a reference to another config key and can be checked.
     */
    private fun kindOf(name: String): UnresolvedPlaceholder.Kind =
        if (ENV_VAR_STYLE.matches(name)) {
            UnresolvedPlaceholder.Kind.EXTERNAL_ENVIRONMENT
        } else {
            UnresolvedPlaceholder.Kind.INTERNAL_REFERENCE
        }

    private fun isSupplied(
        name: String,
        knownKeys: Set<NormalizedKey>,
        declaredKeys: Set<NormalizedKey>,
    ): Boolean {
        val candidates = setOf(
            KeyNormalizer.normalize(name),
            // Env-var spelling: SPRING_DATASOURCE_URL -> spring.datasource.url
            KeyNormalizer.normalize(name.replace('_', '.')),
        )
        return candidates.any { it in knownKeys || it in declaredKeys }
    }

    private companion object {
        val ENV_VAR_STYLE = Regex("""[A-Z][A-Z0-9_]*""")
    }
}
