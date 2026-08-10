package io.github.configdrift.metadata

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.ContainerUtil
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
     * One entry per project, invalidated by comparing each metadata file's modification stamp
     * rather than a fixed TTL. Weak-keyed so a closed project's entry can be collected instead of
     * pinned for the life of the IDE process — this provider is a singleton extension instance
     * that outlives any single project.
     */
    private val cache = ContainerUtil.createConcurrentWeakMap<Project, CacheEntry>()

    private data class CacheEntry(
        val stamps: Map<String, Long>,
        val contracts: Map<NormalizedKey, KeyContract>,
    )

    /**
     * `contractFor` used to be called once per config entry during analysis — hundreds of times
     * on a real project — and, with no caching at all, each call independently re-walked the
     * project's content roots, re-read every metadata file's bytes, and re-ran the hand-rolled
     * JSON parser on all of them. That turned an O(metadata file size) cost into
     * O(config entries × metadata file size), which the TODO this replaces understated: it read
     * as "recomputed once per analysis run," not once per key within a run.
     *
     * The content-root walk itself still happens on every call — VFS traversal is comparatively
     * cheap, since it reads the platform's already in-memory VFS tree rather than touching disk —
     * but the expensive part, reading and parsing file *contents*, is skipped whenever every
     * metadata file's modification stamp matches what was cached.
     *
     * A run in which any file failed to read is deliberately not cached. [readSafely] degrades a
     * failure to "this file declares nothing", which is the right call for one analysis run but
     * the wrong thing to remember: a file momentarily locked by the annotation processor mid-build
     * would otherwise pin an empty contract set until its modification stamp next changed, and a
     * stamp does not change when a *read* fails. Every subsequent analysis would then silently
     * report the project's own properties as undeclared.
     */
    private fun contracts(project: Project): Map<NormalizedKey, KeyContract> {
        val files = ConfigFileDiscovery().discoverMetadataFiles(project)
        val currentStamps = files.associate { it.path to it.modificationStamp }

        cache[project]?.let { entry -> if (entry.stamps == currentStamps) return entry.contracts }

        var complete = true
        val computed = files
            .flatMap { file -> readSafely(file) { complete = false } }
            // Generated metadata wins over hand-written additions on collision, matching the
            // order Spring Boot's own merge produces.
            .associateBy { it.key }
        if (complete) cache[project] = CacheEntry(currentStamps, computed)
        return computed
    }

    private fun readSafely(file: VirtualFile, onFailure: () -> Unit): List<KeyContract> =
        try {
            SpringMetadataReader.read(String(file.contentsToByteArray(), Charsets.UTF_8), providerId)
        } catch (e: ProcessCanceledException) {
            throw e // Must always propagate — swallowing it breaks read-action cancellation.
        } catch (e: Exception) {
            onFailure()
            // Previously only MiniJson.ParseException was caught, so an I/O failure from
            // contentsToByteArray() (the file deleted or locked mid-read, for instance) escaped
            // uncaught: it would propagate out through every analyzer's flatMap in
            // DriftAnalysisEngine.analyze() and fail the *entire* analysis over one metadata file
            // — every MissingKey, SecretExposure, and ShapeMismatch finding lost along with it,
            // none of which have anything to do with metadata. One broken or unreadable file here
            // should cost only this provider's contracts, not the whole run.
            thisLogger().warn("Skipping unreadable or malformed metadata file ${file.path}", e)
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
