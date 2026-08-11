package io.github.configdrift.parser

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.configdrift.model.ConfigDomain
import io.github.configdrift.model.ConfigEntry
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Flattens `services.*.environment` out of a docker-compose file.
 *
 * Narrower than [YamlConfigParser]'s general flattening on purpose: only `environment:` blocks
 * are config-comparison-relevant here, and every entry is keyed as `<service>.<ENV_VAR_NAME>`
 * rather than a bare env var name — `web`'s `DB_HOST` and `worker`'s `DB_HOST` are unrelated
 * variables, not the same key, so the service name has to be part of the identity.
 *
 * `env_file:` references are deliberately not followed — this only reads values written directly
 * in `environment:`, not values pulled in from an external `.env` file at deploy time.
 */
class DockerComposeConfigParser : ConfigFileParser {

    override val domain: ConfigDomain = ConfigDomain.DOCKER_COMPOSE

    override fun supports(file: VirtualFile): Boolean = DockerComposeNaming.matches(file.name)

    override fun parse(support: ParseSupport, psiFile: PsiFile): List<ParsedDocument> {
        val profile = DockerComposeNaming.profileFor(psiFile.name) ?: return emptyList()
        val yamlFile = psiFile as? YAMLFile ?: return emptyList()
        val root = yamlFile.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return emptyList()
        val services = root.keyValueOf("services")?.value as? YAMLMapping ?: return emptyList()

        val entries = services.keyValues.flatMap { serviceEntry ->
            val serviceName = serviceEntry.keyText
            val serviceConfig = serviceEntry.value as? YAMLMapping ?: return@flatMap emptyList()
            val environment = serviceConfig.keyValueOf("environment")?.value
                ?: return@flatMap emptyList()

            environmentEntries(environment).map { (envVarName, rawValue, anchor) ->
                val key = DotenvKeyNormalizer.normalize("$serviceName.$envVarName")
                val location = support.locationOf(psiFile, anchor)
                ConfigEntry(
                    key = key,
                    rawKey = "$serviceName.$envVarName",
                    value = support.valueOf(key, rawValue, profile, location),
                    profile = profile,
                    location = location,
                    domain = domain,
                )
            }
        }

        return listOf(ParsedDocument(profile, entries, support.drainSecretHits()))
    }

    private fun YAMLMapping.keyValueOf(name: String): YAMLKeyValue? =
        keyValues.firstOrNull { it.keyText == name }

    /**
     * One `environment:` block, in either form the Compose Spec allows.
     *
     * A value-less entry (`- DEBUG` in list form, `DEBUG:` in map form) means "pass this variable
     * through from the host environment unchanged" — a genuinely different thing from an empty
     * string, but this model has no way to represent "unknowable at analysis time" any more
     * precisely than [YamlConfigParser] does for a bare Spring `key:`. It's recorded as present
     * with an empty value rather than skipped, so [io.github.configdrift.engine.MissingKeyAnalyzer]
     * still sees that this service references the variable at all.
     */
    private fun environmentEntries(value: YAMLValue): List<Triple<String, String?, PsiElement>> =
        when (value) {
            is YAMLMapping -> value.keyValues.map { entry ->
                val scalarValue = (entry.value as? YAMLScalar)?.textValue
                Triple(entry.keyText, scalarValue, entry as PsiElement)
            }
            is YAMLSequence -> value.items.mapNotNull { item ->
                val raw = (item.value as? YAMLScalar)?.textValue ?: return@mapNotNull null
                val separatorIndex = raw.indexOf('=')
                val anchor = item.value as PsiElement
                if (separatorIndex < 0) {
                    Triple(raw.trim(), null, anchor)
                } else {
                    // The map form's value comes from a real YAMLScalar node, whose textValue is
                    // already YAML-unquoted and doesn't need trimming. This list-form value is just
                    // a substring of one bigger scalar's raw text, split on '=' by hand — without
                    // the same normalization, `- DB_HOST="localhost"` would keep its literal quote
                    // characters (and `- DB_HOST= localhost` its leading space) while the equivalent
                    // `DB_HOST: "localhost"` map form correctly reads as `localhost`, producing a
                    // spurious value drift between two config files that mean the same thing.
                    // DotenvParsing.unquote is the same KEY=VALUE value normalization the sibling
                    // .env parser already applies to this exact syntax.
                    val envKey = raw.substring(0, separatorIndex).trim()
                    val envValue = DotenvParsing.unquote(raw.substring(separatorIndex + 1).trim())
                    Triple(envKey, envValue, anchor)
                }
            }
            else -> emptyList()
        }
}
