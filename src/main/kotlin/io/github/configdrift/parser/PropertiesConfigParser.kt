package io.github.configdrift.parser

import com.intellij.lang.properties.PropertiesImplUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Flattens `application*.properties`.
 *
 * Simpler than YAML: keys are already flat and a `.properties` file cannot be split into
 * documents. It can still retag itself wholesale via `spring.config.activate.on-profile`, which
 * is honoured here.
 */
class PropertiesConfigParser : ConfigFileParser {

    override fun supports(file: VirtualFile): Boolean =
        file.extension == "properties" && ProfileResolver.isConfigFileName(file.name)

    override fun parse(support: ParseSupport, psiFile: PsiFile): List<ParsedDocument> {
        val propertiesFile = PropertiesImplUtil.getPropertiesFile(psiFile) ?: return emptyList()
        val fileProfile = ProfileResolver.fromFileName(psiFile.name) ?: return emptyList()

        val pairs = propertiesFile.properties.mapNotNull { property ->
            val rawKey = property.key ?: return@mapNotNull null
            RawPair(
                rawKey = rawKey,
                key = KeyNormalizer.normalize(rawKey),
                text = property.value,
                element = property.psiElement,
            )
        }.collapseIndexedLists()
        if (pairs.isEmpty()) return emptyList()

        val profile = pairs.declaredProfiles()?.firstOrNull() ?: fileProfile
        return listOf(pairs.toParsedDocument(support, psiFile, profile))
    }
}
