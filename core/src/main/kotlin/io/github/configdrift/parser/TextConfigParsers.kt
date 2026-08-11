package io.github.configdrift.parser

import io.github.configdrift.discovery.ProjectPaths
import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigEntry
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Headless parsers: read file bytes and produce the same [ParsedDocument] model the IDE PSI
 * parsers produce, so [io.github.configdrift.engine.DriftAnalysisEngine] is shared.
 */
object TextConfigParsers {

    fun parseFile(path: Path, root: Path, values: ValueFactory): List<ParsedDocument> {
        val name = path.name
        val content = path.readText(Charsets.UTF_8)
        val relative = ProjectPaths.relativize(root.toString(), path.toAbsolutePath().toString())
            .replace('\\', '/')

        return try {
            when {
                DotenvNaming.matches(name) -> parseDotenv(name, content, relative, values)
                DockerComposeNaming.matches(name) -> parseCompose(name, content, relative, values)
                name.endsWith(".properties") && ProfileResolver.isConfigFileName(name) ->
                    parseProperties(name, content, relative, values)
                (name.endsWith(".yml") || name.endsWith(".yaml")) && ProfileResolver.isConfigFileName(name) ->
                    parseSpringYaml(name, content, relative, values)
                else -> emptyList()
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse config file: $relative (${e.message})", e)
        }
    }

    private fun parseDotenv(
        name: String,
        content: String,
        relative: String,
        values: ValueFactory,
    ): List<ParsedDocument> {
        val profile = DotenvNaming.profileFor(name) ?: return emptyList()
        val domain = ConfigDomain.DOTENV
        var offset = 0
        val entries = content.lineSequence().mapNotNull { line ->
            val lineStart = offset
            offset += line.length + 1 // +1 for '\n' (last line may not have it; close enough)
            val dotenvLine = DotenvParsing.parseLine(line) ?: return@mapNotNull null
            val key = DotenvKeyNormalizer.normalize(dotenvLine.key)
            val location = io.github.configdrift.model.SourceLocation(
                filePath = relative,
                line = lineNumberAt(content, lineStart),
                offset = lineStart,
            )
            ConfigEntry(
                key = key,
                rawKey = dotenvLine.key,
                value = values.valueOf(key, dotenvLine.value, profile, location),
                profile = profile,
                location = location,
                domain = domain,
            )
        }.toList()
        return listOf(ParsedDocument(profile, entries, values.drainSecretHits()))
    }

    private fun parseProperties(
        name: String,
        content: String,
        relative: String,
        values: ValueFactory,
    ): List<ParsedDocument> {
        val fileProfile = ProfileResolver.fromFileName(name) ?: return emptyList()
        val pairs = mutableListOf<TextRawPair>()
        var offset = 0
        for (rawLine in content.lineSequence()) {
            val lineStart = offset
            offset += rawLine.length + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) continue
            val sep = line.indexOf('=').let { eq ->
                val colon = line.indexOf(':')
                when {
                    eq < 0 -> colon
                    colon < 0 -> eq
                    else -> minOf(eq, colon)
                }
            }
            if (sep < 0) continue
            val rawKey = line.substring(0, sep).trim()
            if (rawKey.isEmpty()) continue
            val text = unescapeProperties(line.substring(sep + 1).trim())
            pairs += TextRawPair(
                rawKey = rawKey,
                key = KeyNormalizer.normalize(rawKey),
                text = text,
                offset = lineStart + rawLine.indexOf(rawKey).coerceAtLeast(0),
            )
        }
        val collapsed = pairs.collapseIndexedLists()
        if (collapsed.isEmpty()) return emptyList()
        val profiles = collapsed.declaredProfiles() ?: listOf(fileProfile)
        val first = collapsed.toParsedDocument(values, relative, content, profiles.first(), ConfigDomain.SPRING)
        return listOf(first) + profiles.drop(1).map { first.retagTo(it) }
    }

    private fun unescapeProperties(value: String): String =
        buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '\\' && i + 1 < value.length) {
                    when (val next = value[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        'r' -> append('\r')
                        '\\' -> append('\\')
                        else -> append(next)
                    }
                    i += 2
                } else {
                    append(ch)
                    i++
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseSpringYaml(
        name: String,
        content: String,
        relative: String,
        values: ValueFactory,
    ): List<ParsedDocument> {
        val fileProfile = ProfileResolver.fromFileName(name) ?: return emptyList()
        val softened = softenBareYamlKeys(content)
        val yaml = newYaml()
        val documents = yaml.loadAll(softened).toList()
        val results = mutableListOf<ParsedDocument>()

        for (doc in documents) {
            if (doc == null) continue
            val pairs = flattenYaml(doc, emptyList(), content)
            if (pairs.isEmpty()) continue
            val profiles = pairs.declaredProfiles() ?: listOf(fileProfile)
            val first = pairs.toParsedDocument(values, relative, content, profiles.first(), ConfigDomain.SPRING)
            results += first
            results += profiles.drop(1).map { first.retagTo(it) }
        }
        return results
    }

    /**
     * IntelliJ's YAML PSI accepts a bare key like `aliases[0]` (no colon) as an empty-valued
     * entry; SnakeYAML rejects it. Rewrite those lines to `aliases[0]:` so headless parsing
     * matches the IDE on intentionally quirky fixture files.
     */
    private fun softenBareYamlKeys(content: String): String =
        content.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trimEnd()
            val body = trimmed.trimStart()
            when {
                body.isEmpty() || body.startsWith("#") || body.startsWith("-") -> line
                ':' in body -> line
                BARE_YAML_KEY.matches(body) -> {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    "$indent$body:"
                }
                else -> line
            }
        }

    private val BARE_YAML_KEY = Regex("""[\w.\[\]-]+""")

    private fun newYaml(): Yaml {
        val options = LoaderOptions()
        options.isAllowDuplicateKeys = true
        return Yaml(SafeConstructor(options))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCompose(
        name: String,
        content: String,
        relative: String,
        values: ValueFactory,
    ): List<ParsedDocument> {
        val profile = DockerComposeNaming.profileFor(name) ?: return emptyList()
        val yaml = newYaml()
        val root = yaml.load<Any?>(content) as? Map<*, *> ?: return emptyList()
        val services = root["services"] as? Map<*, *> ?: return emptyList()
        val domain = ConfigDomain.DOCKER_COMPOSE

        val entries = services.entries.flatMap { (serviceKey, serviceVal) ->
            val serviceName = serviceKey?.toString() ?: return@flatMap emptyList()
            val serviceMap = serviceVal as? Map<*, *> ?: return@flatMap emptyList()
            val environment = serviceMap["environment"] ?: return@flatMap emptyList()
            composeEnvironmentEntries(environment).map { (envVarName, rawValue) ->
                val key = DotenvKeyNormalizer.normalize("$serviceName.$envVarName")
                val offset = content.indexOf(envVarName).coerceAtLeast(0)
                val location = io.github.configdrift.model.SourceLocation(
                    filePath = relative,
                    line = lineNumberAt(content, offset),
                    offset = offset,
                )
                ConfigEntry(
                    key = key,
                    rawKey = "$serviceName.$envVarName",
                    value = values.valueOf(key, rawValue, profile, location),
                    profile = profile,
                    location = location,
                    domain = domain,
                )
            }
        }
        return listOf(ParsedDocument(profile, entries, values.drainSecretHits()))
    }

    private fun composeEnvironmentEntries(value: Any?): List<Pair<String, String?>> =
        when (value) {
            is Map<*, *> -> value.entries.map { (k, v) ->
                k.toString() to v?.toString()
            }
            is List<*> -> value.mapNotNull { item ->
                val raw = item?.toString() ?: return@mapNotNull null
                val separatorIndex = raw.indexOf('=')
                if (separatorIndex < 0) {
                    raw.trim() to null
                } else {
                    val envKey = raw.substring(0, separatorIndex).trim()
                    val envValue = DotenvParsing.unquote(raw.substring(separatorIndex + 1).trim())
                    envKey to envValue
                }
            }
            else -> emptyList()
        }

    @Suppress("UNCHECKED_CAST")
    private fun flattenYaml(value: Any?, path: List<String>, content: String): List<TextRawPair> =
        when (value) {
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    single(path, content, text = null, shape = io.github.configdrift.model.ValueShape.MAP)
                } else {
                    value.entries.flatMap { (k, v) ->
                        flattenYaml(v, path + k.toString(), content)
                    }
                }
            }
            is List<*> -> flattenYamlList(value, path, content)
            null -> single(path, content, text = null, shape = null)
            else -> single(path, content, text = value.toString(), shape = null)
        }

    private fun flattenYamlList(value: List<*>, path: List<String>, content: String): List<TextRawPair> =
        when {
            value.isEmpty() ->
                single(path, content, text = null, shape = io.github.configdrift.model.ValueShape.LIST)
            value.all { it !is Map<*, *> && it !is List<*> } ->
                single(
                    path,
                    content,
                    text = value.joinToString(",") { it?.toString().orEmpty() },
                    shape = io.github.configdrift.model.ValueShape.LIST,
                )
            else -> value.flatMapIndexed { index, item ->
                flattenYaml(item, path + "[$index]", content)
            }
        }

    private fun single(
        path: List<String>,
        content: String,
        text: String?,
        shape: io.github.configdrift.model.ValueShape?,
    ): List<TextRawPair> {
        if (path.isEmpty()) return emptyList()
        val rawKey = joinPath(path)
        val needle = path.last().removePrefix("[").removeSuffix("]")
        val offset = content.indexOf(needle).coerceAtLeast(0)
        return listOf(
            TextRawPair(
                rawKey = rawKey,
                key = KeyNormalizer.normalize(rawKey),
                text = text,
                offset = offset,
                shapeOverride = shape,
            ),
        )
    }
}
