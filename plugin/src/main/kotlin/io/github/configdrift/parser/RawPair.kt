package io.github.configdrift.parser

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigEntry
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.ValueShape

/**
 * A flattened key/value pair before its profile is known (PSI path).
 *
 * The two-phase shape exists because a YAML document declares its own profile via
 * `spring.config.activate.on-profile`, which is itself one of the flattened pairs — so the
 * profile can only be decided after flattening, while [ConfigEntry] requires it up front.
 */
data class RawPair(
    val rawKey: String,
    val key: NormalizedKey,
    val text: String?,
    val element: PsiElement,
    val shapeOverride: ValueShape? = null,
)

/**
 * Second phase: attach the resolved profile and run every value through
 * [ParseSupport.valueOf], which is where secrets get destroyed.
 */
fun List<RawPair>.toParsedDocument(
    support: ParseSupport,
    psiFile: PsiFile,
    profile: ProfileId,
    domain: ConfigDomain,
): ParsedDocument {
    val onProfileKey = KeyNormalizer.normalize(ProfileResolver.ON_PROFILE_KEY)

    val entries = asSequence()
        .filter { it.key != onProfileKey }
        .map { pair ->
            val location = support.locationOf(psiFile, pair.element)
            ConfigEntry(
                key = pair.key,
                rawKey = pair.rawKey,
                value = support.valueOf(
                    key = pair.key,
                    rawText = pair.text,
                    profile = profile,
                    location = location,
                    shapeOverride = pair.shapeOverride,
                ),
                profile = profile,
                location = location,
                domain = domain,
            )
        }
        .toList()

    return ParsedDocument(
        profile = profile,
        entries = entries,
        secretHits = support.drainSecretHits(),
    )
}

/**
 * Collapses `app.hosts[0]`, `app.hosts[1]` into one LIST entry named `app.hosts`.
 */
fun List<RawPair>.collapseIndexedLists(): List<RawPair> {
    val indexSuffix = Regex("""^(.*)\[\d+]$""")
    val result = mutableListOf<RawPair>()
    val listMembers = LinkedHashMap<String, MutableList<RawPair>>()

    for (pair in this) {
        val base = indexSuffix.matchEntire(pair.rawKey)?.groupValues?.get(1)
        if (base == null) result += pair else listMembers.getOrPut(base) { mutableListOf() } += pair
    }

    for ((base, members) in listMembers) {
        result += RawPair(
            rawKey = base,
            key = KeyNormalizer.normalize(base),
            text = members.joinToString(",") { it.text.orEmpty() },
            element = members.first().element,
            shapeOverride = ValueShape.LIST,
        )
    }
    return result
}

/** The `on-profile` value inside a flattened document, if it declares one. */
fun List<RawPair>.declaredProfiles(): List<ProfileId>? {
    val onProfileKey = KeyNormalizer.normalize(ProfileResolver.ON_PROFILE_KEY)
    val text = firstOrNull { it.key == onProfileKey }?.text ?: return null
    return ProfileResolver.fromOnProfileExpression(text)
}
