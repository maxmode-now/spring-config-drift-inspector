package io.github.configdrift.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Exit-code threshold for `config-drift check`. */
enum class FailOnThreshold {
    ERROR, WARNING, NEVER;

    companion object {
        fun parse(raw: String): FailOnThreshold = when (raw.lowercase()) {
            "error" -> ERROR
            "warning" -> WARNING
            "never" -> NEVER
            else -> throw IllegalArgumentException(
                "Unknown fail-on: $raw (expected error|warning|never)",
            )
        }
    }
}

/**
 * Optional fields from `.config-drift.yml`. `null` means the key was absent from the file
 * (so CLI defaults / flags decide); a non-null empty set means the file set an empty list.
 */
data class ConfigDriftProjectConfig(
    val failOn: FailOnThreshold? = null,
    /** Relative to the config file's parent directory, or absolute. */
    val path: String? = null,
    val completeProfiles: Set<String>? = null,
    val overlayProfiles: Set<String>? = null,
) {
    companion object {
        val EMPTY = ConfigDriftProjectConfig()

        private val KNOWN_TOP_LEVEL = setOf("fail-on", "fail_on", "path", "profiles")

        fun discover(directory: Path): Path? {
            val yml = directory.resolve(".config-drift.yml")
            if (yml.isRegularFile()) return yml
            val yaml = directory.resolve(".config-drift.yaml")
            if (yaml.isRegularFile()) return yaml
            return null
        }

        fun load(file: Path): ConfigDriftProjectConfig {
            if (!Files.isRegularFile(file)) {
                throw IllegalArgumentException("Config file not found: $file")
            }
            return parse(file.readText(Charsets.UTF_8))
        }

        fun parse(yamlText: String): ConfigDriftProjectConfig {
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val root = yaml.load<Any?>(yamlText) ?: return EMPTY
            if (root !is Map<*, *>) {
                throw IllegalArgumentException(".config-drift.yml root must be a mapping")
            }

            val unknown = root.keys.mapNotNull { it?.toString() }.filter { it !in KNOWN_TOP_LEVEL }
            if (unknown.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Unknown key(s) in .config-drift.yml: ${unknown.sorted().joinToString(", ")} " +
                        "(allowed: fail-on, path, profiles)",
                )
            }

            val failOnRaw = root["fail-on"] ?: root["fail_on"]
            val failOn = failOnRaw?.let {
                FailOnThreshold.parse(it.toString())
            }

            val path = root["path"]?.toString()

            var complete: Set<String>? = null
            var overlay: Set<String>? = null
            val profilesNode = root["profiles"]
            if (profilesNode != null) {
                if (profilesNode !is Map<*, *>) {
                    throw IllegalArgumentException("profiles must be a mapping with complete/overlay lists")
                }
                val profileUnknown = profilesNode.keys.mapNotNull { it?.toString() }
                    .filter { it !in setOf("complete", "overlay") }
                if (profileUnknown.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "Unknown key(s) under profiles: ${profileUnknown.sorted().joinToString(", ")}",
                    )
                }
                if (profilesNode.containsKey("complete")) {
                    complete = stringSet(profilesNode["complete"], "profiles.complete")
                }
                if (profilesNode.containsKey("overlay")) {
                    overlay = stringSet(profilesNode["overlay"], "profiles.overlay")
                }
            }

            val overlap = (complete.orEmpty()).intersect(overlay.orEmpty())
            if (overlap.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Profile(s) listed under both profiles.complete and profiles.overlay: " +
                        overlap.sorted().joinToString(", "),
                )
            }

            return ConfigDriftProjectConfig(
                failOn = failOn,
                path = path,
                completeProfiles = complete,
                overlayProfiles = overlay,
            )
        }

        private fun stringSet(node: Any?, field: String): Set<String> {
            if (node == null) return emptySet()
            if (node !is List<*>) {
                throw IllegalArgumentException("$field must be a list of strings")
            }
            return node.mapIndexed { index, item ->
                item?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("$field[$index] must be a non-empty string")
            }.toSet()
        }
    }
}

/**
 * Effective settings after merging defaults, optional project file, and explicit CLI flags.
 *
 * Precedence: defaults → file → explicit CLI (CLI wins per axis when the flag was passed).
 */
data class ResolvedCheckSettings(
    val analysisRoot: Path,
    val failOn: FailOnThreshold,
    val completeProfiles: Set<String>,
    val overlayProfiles: Set<String>,
)

object ConfigDriftSettingsResolver {

    fun resolve(
        /** Directory used to discover `.config-drift.yml` when [configFile] is null and [useConfig] is true. */
        initialPath: Path,
        pathExplicit: Boolean,
        failOnDefault: FailOnThreshold = FailOnThreshold.ERROR,
        failOnFromCli: FailOnThreshold? = null,
        completeFromCli: Set<String>? = null,
        overlayFromCli: Set<String>? = null,
        configFile: Path? = null,
        useConfig: Boolean = true,
    ): ResolvedCheckSettings {
        val start = initialPath.toAbsolutePath().normalize()
        val loadedFile: Path? = when {
            !useConfig -> null
            configFile != null -> configFile.toAbsolutePath().normalize()
            else -> ConfigDriftProjectConfig.discover(start)
        }
        val fileConfig = loadedFile?.let { ConfigDriftProjectConfig.load(it) }
            ?: ConfigDriftProjectConfig.EMPTY

        val analysisRoot = if (!pathExplicit && fileConfig.path != null) {
            val base = loadedFile?.parent ?: start
            base.resolve(fileConfig.path).toAbsolutePath().normalize()
        } else {
            start
        }

        val failOn = failOnFromCli ?: fileConfig.failOn ?: failOnDefault
        val complete = completeFromCli ?: fileConfig.completeProfiles.orEmpty()
        val overlay = overlayFromCli ?: fileConfig.overlayProfiles.orEmpty()

        val overlap = complete.intersect(overlay)
        if (overlap.isNotEmpty()) {
            throw IllegalArgumentException(
                "Profile(s) listed as both complete and overlay: ${overlap.sorted().joinToString(", ")}",
            )
        }

        return ResolvedCheckSettings(
            analysisRoot = analysisRoot,
            failOn = failOn,
            completeProfiles = complete,
            overlayProfiles = overlay,
        )
    }
}
