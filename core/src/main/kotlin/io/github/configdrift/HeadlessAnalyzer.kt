package io.github.configdrift

import io.github.configdrift.discovery.FsConfigDiscovery
import io.github.configdrift.engine.AnalysisContext
import io.github.configdrift.engine.DriftAnalysisEngine
import io.github.configdrift.engine.OverlayOverrides
import io.github.configdrift.metadata.FileMetadataLoader
import io.github.configdrift.model.ConfigSnapshot
import io.github.configdrift.model.DriftReport
import io.github.configdrift.model.ProfileSnapshot
import io.github.configdrift.parser.TextConfigParsers
import io.github.configdrift.parser.ValueFactory
import io.github.configdrift.secrets.SecretDetector
import io.github.configdrift.spi.ContractCatalog
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Headless analysis entry point shared by the CLI (and any future non-IDE adapters).
 */
object HeadlessAnalyzer {

    fun analyze(
        root: Path,
        overlayOverrides: OverlayOverrides = OverlayOverrides.NONE,
        contracts: ContractCatalog? = null,
        projectName: String = root.toAbsolutePath().normalize().name,
    ): DriftReport {
        val values = ValueFactory(SecretDetector())
        val documents = FsConfigDiscovery.discoverConfigFiles(root).flatMap { path ->
            TextConfigParsers.parseFile(path, root, values)
        }

        val snapshot = ConfigSnapshot(
            documents.groupBy { it.profile }
                .map { (profile, docs) ->
                    ProfileSnapshot(profile, docs.flatMap { it.entries })
                },
        )

        val context = AnalysisContext(
            snapshot = snapshot,
            secretHits = documents.flatMap { it.secretHits },
            contracts = contracts ?: FileMetadataLoader.load(root),
            overlayOverrides = overlayOverrides,
        )

        return DriftAnalysisEngine().analyze(projectName, context)
    }
}
