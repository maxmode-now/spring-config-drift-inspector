package io.github.configdrift.parser

import com.intellij.lang.properties.PropertiesImplUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.configdrift.model.ConfigDomain

/**
 * Flattens `application*.properties`.
 *
 * Simpler than YAML: keys are already flat and a `.properties` file cannot be split into
 * documents. It can still retag itself wholesale via `spring.config.activate.on-profile`, which
 * is honoured here — including activating for several profiles at once (`on-profile=dev|stage`),
 * the same as a YAML document does.
 */
class PropertiesConfigParser : ConfigFileParser {

    override val domain: ConfigDomain = ConfigDomain.SPRING

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

        val profiles = pairs.declaredProfiles() ?: listOf(fileProfile)
        val first = pairs.toParsedDocument(support, psiFile, profiles.first(), domain)
        return listOf(first) + profiles.drop(1).map { first.retagTo(it) }
    }
}
