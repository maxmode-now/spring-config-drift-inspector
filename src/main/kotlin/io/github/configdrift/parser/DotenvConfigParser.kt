package io.github.configdrift.parser

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.configdrift.model.ConfigEntry

/**
 * Flattens `.env` files — the dotenv convention used across Node, Go, Python, and other
 * non-Spring backends for per-environment configuration.
 *
 * Unlike [YamlConfigParser]/[PropertiesConfigParser], there is no PSI language for `.env` files to
 * parse against, so this reads line offsets straight from the [PsiFile]'s [com.intellij.openapi.editor.Document]
 * instead — see [ParseSupport.locationOf]'s offset overload.
 */
class DotenvConfigParser : ConfigFileParser {

    override fun supports(file: VirtualFile): Boolean = DotenvNaming.matches(file.name)

    override fun parse(support: ParseSupport, psiFile: PsiFile): List<ParsedDocument> {
        val profile = DotenvNaming.profileFor(psiFile.name) ?: return emptyList()
        val document = psiFile.viewProvider.document ?: return emptyList()

        val entries = (0 until document.lineCount).mapNotNull { lineIndex ->
            val start = document.getLineStartOffset(lineIndex)
            val end = document.getLineEndOffset(lineIndex)
            val dotenvLine = DotenvParsing.parseLine(document.getText(TextRange(start, end)))
                ?: return@mapNotNull null

            val location = support.locationOf(psiFile, start)
            ConfigEntry(
                key = DotenvKeyNormalizer.normalize(dotenvLine.key),
                rawKey = dotenvLine.key,
                value = support.valueOf(
                    key = DotenvKeyNormalizer.normalize(dotenvLine.key),
                    rawText = dotenvLine.value,
                    profile = profile,
                    location = location,
                ),
                profile = profile,
                location = location,
            )
        }

        return listOf(ParsedDocument(profile, entries, support.drainSecretHits()))
    }
}
