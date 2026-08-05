package io.github.configdrift.metadata

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.configdrift.discovery.ConfigFileDiscovery
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.parser.KeyNormalizer
import io.github.configdrift.spi.BindingContractProvider
import io.github.configdrift.spi.KeyContract

/**
 * The MVP's only [BindingContractProvider]: it reads `spring-configuration-metadata.json`.
 *
 * Registered through the same extension point third parties would use, so the eventual
 * PSI-based `@ConfigurationProperties` provider slots in beside it without engine changes.
 */
class MetadataContractProvider : BindingContractProvider {

    override val providerId: String = "spring-configuration-metadata"

    override fun contractFor(project: Project, key: NormalizedKey): KeyContract? =
        contracts(project)[key]

    override fun declaredKeys(project: Project): Set<NormalizedKey> = contracts(project).keys

    /**
     * TODO(perf): back this with CachedValuesManager keyed on the metadata files' modification
     * stamps. Recomputing per analysis run is acceptable while runs are user-triggered; it will
     * not be once this feeds a live inspection.
     */
    private fun contracts(project: Project): Map<NormalizedKey, KeyContract> =
        ConfigFileDiscovery().discoverMetadataFiles(project)
            .flatMap { readSafely(it) }
            // Generated metadata wins over hand-written additions on collision, matching the
            // order Spring Boot's own merge produces.
            .associateBy { it.key }

    private fun readSafely(file: VirtualFile): List<KeyContract> =
        try {
            SpringMetadataReader.read(String(file.contentsToByteArray(), Charsets.UTF_8), providerId)
        } catch (e: MiniJson.ParseException) {
            thisLogger().warn("Skipping malformed metadata file ${file.path}", e)
            emptyList()
        }
}

/** Extracts the `properties` section of a Spring configuration metadata document. */
object SpringMetadataReader {

    fun read(json: String, providerId: String): List<KeyContract> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val properties = root["properties"] as? List<*> ?: return emptyList()

        return properties.mapNotNull { entry ->
            val property = entry as? Map<*, *> ?: return@mapNotNull null
            val name = property["name"] as? String ?: return@mapNotNull null
            KeyContract(
                key = KeyNormalizer.normalize(name),
                declaredType = property["type"] as? String,
                defaultValue = property["defaultValue"]?.toString(),
                deprecated = property["deprecated"] == true || property["deprecation"] != null,
                sourceProviderId = providerId,
            )
        }
    }
}
