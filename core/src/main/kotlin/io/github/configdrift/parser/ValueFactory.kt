package io.github.configdrift.parser

import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigEntry
import io.github.configdrift.model.ConfigValue
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.SourceLocation
import io.github.configdrift.model.ValueShape
import io.github.configdrift.secrets.Masker
import io.github.configdrift.secrets.SecretDetector

/** Entries grouped by the profile they belong to. One file can yield several of these. */
data class ParsedDocument(
    val profile: ProfileId,
    val entries: List<ConfigEntry>,
    /** Populated when a value was redacted, so the engine can raise a SecretExposure. */
    val secretHits: List<SecretHit>,
)

/** Recorded at redaction time; carries the masked form only. */
data class SecretHit(
    val key: NormalizedKey,
    val profile: ProfileId,
    val location: SourceLocation,
    val ruleId: String,
    val masked: String,
)

/**
 * Shared services for parsers: location helpers and the single point where a plaintext value is
 * either kept or destroyed. IDE-free so CLI text parsers and IntelliJ PSI parsers share one path.
 */
class ValueFactory(
    private val secretDetector: SecretDetector = SecretDetector(),
) {
    private val hits = mutableListOf<SecretHit>()

    fun drainSecretHits(): List<SecretHit> = hits.toList().also { hits.clear() }

    /**
     * Builds the stored value. If [secretDetector] fires, the plaintext is converted to a digest
     * and length here and is not returned to the caller in any form — this is the enforcement
     * point for the masking guarantee, not a later rendering step.
     */
    fun valueOf(
        key: NormalizedKey,
        rawText: String?,
        profile: ProfileId,
        location: SourceLocation,
        /** Set for empty containers (`a: {}` / `a: []`), where scalar inference cannot apply. */
        shapeOverride: ValueShape? = null,
    ): ConfigValue {
        val shape = shapeOverride ?: ValueShapes.ofScalar(rawText)
        val match = secretDetector.detect(key, rawText)
        if (match == null || rawText == null) {
            return ConfigValue.Plain(rawText, shape)
        }

        // Mask and digest the committed secret, not the surrounding expression, so a
        // `${VAR:secret}` value reports the secret's length rather than the expression's.
        val masked = Masker.mask(match.committedValue)
        hits += SecretHit(key, profile, location, match.rule.id, masked)
        return ConfigValue.redact(match.committedValue, shape)
    }
}

/** Scalar type inference. Intentionally shallow: enough to catch drift, not a Binder model. */
object ValueShapes {

    private val INTEGER = Regex("""[+-]?\d+""")
    private val DECIMAL = Regex("""[+-]?(\d+\.\d*|\.\d+)([eE][+-]?\d+)?""")

    fun ofScalar(text: String?): ValueShape {
        val value = text?.trim() ?: return ValueShape.NULL
        return when {
            value.isEmpty() -> ValueShape.NULL
            value.equals("null", ignoreCase = true) || value == "~" -> ValueShape.NULL
            value.equals("true", ignoreCase = true) ||
                value.equals("false", ignoreCase = true) -> ValueShape.BOOLEAN
            INTEGER.matches(value) -> ValueShape.INTEGER
            DECIMAL.matches(value) -> ValueShape.DECIMAL
            else -> ValueShape.STRING
        }
    }
}

/**
 * A flattened key/value pair before its profile is known (text / headless path).
 *
 * Offset is the character index in the source file used for jump-to-source / report location.
 */
data class TextRawPair(
    val rawKey: String,
    val key: NormalizedKey,
    val text: String?,
    val offset: Int,
    val shapeOverride: ValueShape? = null,
)

fun List<TextRawPair>.toParsedDocument(
    values: ValueFactory,
    filePath: String,
    content: String,
    profile: ProfileId,
    domain: ConfigDomain,
): ParsedDocument {
    val onProfileKey = KeyNormalizer.normalize(ProfileResolver.ON_PROFILE_KEY)

    val entries = asSequence()
        .filter { it.key != onProfileKey }
        .map { pair ->
            val location = SourceLocation(
                filePath = filePath,
                line = lineNumberAt(content, pair.offset),
                offset = pair.offset,
            )
            ConfigEntry(
                key = pair.key,
                rawKey = pair.rawKey,
                value = values.valueOf(
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
        secretHits = values.drainSecretHits(),
    )
}

fun List<TextRawPair>.collapseIndexedLists(): List<TextRawPair> {
    val indexSuffix = Regex("""^(.*)\[\d+]$""")
    val result = mutableListOf<TextRawPair>()
    val listMembers = LinkedHashMap<String, MutableList<TextRawPair>>()

    for (pair in this) {
        val base = indexSuffix.matchEntire(pair.rawKey)?.groupValues?.get(1)
        if (base == null) result += pair else listMembers.getOrPut(base) { mutableListOf() } += pair
    }

    for ((base, members) in listMembers) {
        result += TextRawPair(
            rawKey = base,
            key = KeyNormalizer.normalize(base),
            text = members.joinToString(",") { it.text.orEmpty() },
            offset = members.first().offset,
            shapeOverride = ValueShape.LIST,
        )
    }
    return result
}

fun List<TextRawPair>.declaredProfiles(): List<ProfileId>? {
    val onProfileKey = KeyNormalizer.normalize(ProfileResolver.ON_PROFILE_KEY)
    val text = firstOrNull { it.key == onProfileKey }?.text ?: return null
    return ProfileResolver.fromOnProfileExpression(text)
}

fun ParsedDocument.retagTo(profile: ProfileId): ParsedDocument =
    ParsedDocument(
        profile = profile,
        entries = entries.map { it.copy(profile = profile) },
        secretHits = secretHits.map { it.copy(profile = profile) },
    )

fun lineNumberAt(content: String, offset: Int): Int {
    if (offset <= 0) return 1
    var line = 1
    val limit = minOf(offset, content.length)
    for (i in 0 until limit) {
        if (content[i] == '\n') line++
    }
    return line
}

fun joinPath(path: List<String>): String = buildString {
    for (segment in path) {
        if (isNotEmpty() && !segment.startsWith("[")) append('.')
        append(segment)
    }
}
