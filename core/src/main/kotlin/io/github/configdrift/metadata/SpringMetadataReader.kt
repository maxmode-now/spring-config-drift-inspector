package io.github.configdrift.metadata

import io.github.configdrift.parser.KeyNormalizer
import io.github.configdrift.spi.KeyContract

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
