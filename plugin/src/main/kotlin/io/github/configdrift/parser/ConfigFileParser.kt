package io.github.configdrift.parser

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.configdrift.discovery.ProjectPaths
import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigValue
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.model.ProfileId
import io.github.configdrift.model.SourceLocation
import io.github.configdrift.model.ValueShape
import io.github.configdrift.secrets.SecretDetector

/**
 * Turns one config file into flattened, profile-tagged entries.
 *
 * Implementations work on PSI rather than a standalone YAML/properties library so that every
 * entry carries a real text offset — that is what makes "jump to source" exact instead of a
 * best-effort line guess.
 */
interface ConfigFileParser {
    /**
     * Which config system this parser reads. Every [io.github.configdrift.model.ConfigEntry] it
     * produces is tagged with it, so the engine can keep "missing" comparisons inside one system —
     * see [ConfigDomain].
     */
    val domain: ConfigDomain

    fun supports(file: VirtualFile): Boolean

    fun parse(support: ParseSupport, psiFile: PsiFile): List<ParsedDocument>
}

/**
 * IDE-facing wrapper around [ValueFactory]: location computation from PSI plus the shared
 * masking path.
 */
class ParseSupport(
    val project: Project,
    secretDetector: SecretDetector = SecretDetector(),
) {
    private val values = ValueFactory(secretDetector)

    fun drainSecretHits(): List<SecretHit> = values.drainSecretHits()

    fun locationOf(psiFile: PsiFile, element: PsiElement): SourceLocation =
        locationOf(psiFile, element.textRange.startOffset)

    /**
     * For formats with no PSI language of their own (`.env`), so there is no [PsiElement] to read
     * an offset from in the first place.
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

    fun valueOf(
        key: NormalizedKey,
        rawText: String?,
        profile: ProfileId,
        location: SourceLocation,
        shapeOverride: ValueShape? = null,
    ): ConfigValue = values.valueOf(key, rawText, profile, location, shapeOverride)

    private fun relativePath(file: VirtualFile?): String {
        val path = file?.path ?: return "<unknown>"
        return ProjectPaths.relativize(project.basePath, path)
    }
}
