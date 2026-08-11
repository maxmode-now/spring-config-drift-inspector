package io.github.configdrift.parser

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigEntry
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.ValueShape

/**
 * A flattened key/value pair before its profile is known.
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
 *
 * The YAML parser collapses scalar sequences while walking the tree; `.properties` files arrive
 * already flat, so the same normalization has to happen after the fact. Without it the two
 * formats would disagree about what a list key is called, and every list would look like drift
 * between a YAML profile and a properties profile.
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
            // Anchor on the first element so navigation lands inside the list.
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

/**
 * Reuses already-parsed entries for an additional profile the same document activates for
 * (`on-profile: "dev|stage"` activates for both, not just the first).
 *
 * Shared by both parsers rather than living in `YamlConfigParser` alone: it was there first
 * because YAML's `---` documents were the motivating case, but a `.properties` file honours the
 * same `on-profile` key and needs the identical fan-out — a properties-only implementation that
 * called `.firstOrNull()` on the declared profile list silently dropped every profile after the
 * first.
 */
fun ParsedDocument.retagTo(profile: ProfileId): ParsedDocument =
    ParsedDocument(
        profile = profile,
        entries = entries.map { it.copy(profile = profile) },
        secretHits = secretHits.map { it.copy(profile = profile) },
    )
