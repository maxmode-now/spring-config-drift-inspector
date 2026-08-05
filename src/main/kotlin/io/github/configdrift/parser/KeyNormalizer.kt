package io.github.configdrift.parser

import io.github.configdrift.model.NormalizedKey

/**
 * Collapses the spellings Spring treats as one property into a single comparable key.
 *
 * Without this every analyzer produces false positives: `spring.datasource.driverClassName` in
 * `application.yml` and `spring.datasource.driver-class-name` in `application-prod.yml` are the
 * same property, and reporting one as "missing in prod" would make the tool useless.
 *
 * Rules, applied per dot-separated segment:
 *  - lower-cased
 *  - `-` and `_` removed, so `driver-class-name`, `driverClassName`, and `DRIVER_CLASS_NAME`
 *    all become `driverclassname`
 *  - `[n]` index suffixes preserved, so list positions stay distinguishable
 *  - map keys inside `[...]` left verbatim, because those are data, not property names
 *
 * Known limitation: environment-variable spelling is lossy. `SPRING_DATASOURCE_URL` cannot be
 * distinguished from a literal segment `spring_datasource_url`, because `_` stands for both `.`
 * and `-` in that form. This only matters if a *file* uses env-var style keys, which is rare;
 * resolving it properly needs the declared key set, so it is left to a
 * [io.github.configdrift.spi.BindingContractProvider].
 */
object KeyNormalizer {

    private val INDEX_SUFFIX = Regex("""\[([^]]*)]$""")

    fun normalize(rawKey: String): NormalizedKey =
        NormalizedKey(
            rawKey.split('.')
                .filter { it.isNotEmpty() }
                .joinToString(".") { normalizeSegment(it) },
        )

    private fun normalizeSegment(segment: String): String {
        val match = INDEX_SUFFIX.find(segment)
            ?: return canonical(segment)

        val name = segment.substring(0, match.range.first)
        // Numeric list indices are positional and map keys are data: both stay verbatim.
        val index = match.groupValues[1]
        return canonical(name) + "[" + index + "]"
    }

    private fun canonical(segment: String): String =
        buildString(segment.length) {
            for (ch in segment) {
                when (ch) {
                    '-', '_' -> Unit
                    else -> append(ch.lowercaseChar())
                }
            }
        }

    /** Joins a nested YAML path into a flat property key before normalizing. */
    fun fromPath(path: List<String>): NormalizedKey = normalize(path.joinToString("."))
}
