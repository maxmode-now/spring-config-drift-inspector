package io.github.configdrift.metadata

import io.github.configdrift.discovery.FsConfigDiscovery
import io.github.configdrift.spi.ContractCatalog
import io.github.configdrift.spi.KeyContract
import java.nio.file.Path
import kotlin.io.path.readText

/** Loads Spring configuration metadata JSON files from disk into a [ContractCatalog]. */
object FileMetadataLoader {

    private const val PROVIDER_ID = "spring-configuration-metadata"

    fun load(root: Path): ContractCatalog {
        val contracts = linkedMapOf<io.github.configdrift.model.NormalizedKey, KeyContract>()
        for (file in FsConfigDiscovery.discoverMetadataFiles(root)) {
            try {
                for (contract in SpringMetadataReader.read(file.readText(Charsets.UTF_8), PROVIDER_ID)) {
                    contracts[contract.key] = contract
                }
            } catch (_: Exception) {
                // One broken metadata file must not abort the whole analysis.
            }
        }
        return ContractCatalog(contracts)
    }
}
