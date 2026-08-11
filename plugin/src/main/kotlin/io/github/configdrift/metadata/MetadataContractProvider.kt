package io.github.configdrift.metadata

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.ContainerUtil
import io.github.configdrift.discovery.ConfigFileDiscovery
import io.github.configdrift.model.NormalizedKey
import io.github.configdrift.spi.BindingContractProvider
import io.github.configdrift.spi.KeyContract

/**
 * Reads `spring-configuration-metadata.json` / `additional-spring-configuration-metadata.json`
 * from the project's VFS.
 */
class MetadataContractProvider : BindingContractProvider {

    override val providerId: String = "spring-configuration-metadata"

    override fun contractFor(project: Project, key: NormalizedKey): KeyContract? =
        contracts(project)[key]

    override fun declaredKeys(project: Project): Set<NormalizedKey> = contracts(project).keys

    private val cache = ContainerUtil.createConcurrentWeakMap<Project, CacheEntry>()

    private data class CacheEntry(
        val stamps: Map<String, Long>,
        val contracts: Map<NormalizedKey, KeyContract>,
    )

    private fun contracts(project: Project): Map<NormalizedKey, KeyContract> {
        val files = ConfigFileDiscovery().discoverMetadataFiles(project)
        val currentStamps = files.associate { it.path to it.modificationStamp }

        cache[project]?.let { entry -> if (entry.stamps == currentStamps) return entry.contracts }

        var complete = true
        val computed = files
            .flatMap { file -> readSafely(file) { complete = false } }
            .associateBy { it.key }
        if (complete) cache[project] = CacheEntry(currentStamps, computed)
        return computed
    }

    private fun readSafely(file: VirtualFile, onFailure: () -> Unit): List<KeyContract> =
        try {
            SpringMetadataReader.read(String(file.contentsToByteArray(), Charsets.UTF_8), providerId)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            onFailure()
            thisLogger().warn("Skipping unreadable or malformed metadata file ${file.path}", e)
            emptyList()
        }
}
