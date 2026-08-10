package io.github.configdrift.parser

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.configdrift.discovery.ProjectPaths
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
 * Turns one config file into flattened, profile-tagged entries.
 *
 * Implementations work on PSI rather than a standalone YAML/properties library so that every
 * entry carries a real text offset — that is what makes "jump to source" exact instead of a
 * best-effort line guess.
 */
interface ConfigFileParser {
    fun supports(file: VirtualFile): Boolean

    fun parse(support: ParseSupport, psiFile: PsiFile): List<ParsedDocument>
}

/**
 * Shared services for parsers: location computation and — critically — the single point where a
 * plaintext value is either kept or destroyed.
 */
class ParseSupport(
    val project: Project,
    private val secretDetector: SecretDetector,
) {
    private val hits = mutableListOf<SecretHit>()

    fun drainSecretHits(): List<SecretHit> = hits.toList().also { hits.clear() }

    fun locationOf(psiFile: PsiFile, element: PsiElement): SourceLocation =
        locationOf(psiFile, element.textRange.startOffset)

    /**
     * For formats with no PSI language of their own (`.env`), so there is no [PsiElement] to read
     * an offset from in the first place. `OpenFileDescriptor` (what [io.github.configdrift.ui.SourceNavigator]
     * ultimately opens) only ever needs an integer offset, so jump-to-source is exactly as precise
     * here as it is for the PSI-backed formats — this overload only skips the unnecessary
     * intermediate `PsiElement`.
     */
    fun locationOf(psiFile: PsiFile, offset: Int): SourceLocation {
        val document = psiFile.viewProvider.document
        val line = document?.getLineNumber(offset)?.plus(1) ?: 1
        return SourceLocation(
            filePath = relativePath(psiFile.virtualFile),
            line = line,
            offset = offset,
        )
    }

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

    private fun relativePath(file: VirtualFile?): String {
        val path = file?.path ?: return "<unknown>"
        return ProjectPaths.relativize(project.basePath, path)
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
