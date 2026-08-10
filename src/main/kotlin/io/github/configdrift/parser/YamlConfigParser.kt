package io.github.configdrift.parser

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.configdrift.model.ValueShape
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Flattens `application*.yml` into dotted keys.
 *
 * Handles the case a filename-only implementation misses: a single file split by `---` into
 * several documents, each selecting its own profile through
 * `spring.config.activate.on-profile`. Those documents become separate profiles even though they
 * share a filename.
 *
 * Not handled in the MVP: anchors and aliases (`*ref`) are recorded as their literal text rather
 * than resolved, and multi-document merge order beyond last-wins within a profile.
 */
class YamlConfigParser : ConfigFileParser {

    override fun supports(file: VirtualFile): Boolean =
        file.extension in YAML_EXTENSIONS && ProfileResolver.isConfigFileName(file.name)

    override fun parse(support: ParseSupport, psiFile: PsiFile): List<ParsedDocument> {
        val yamlFile = psiFile as? YAMLFile ?: return emptyList()
        val fileProfile = ProfileResolver.fromFileName(psiFile.name) ?: return emptyList()

        return yamlFile.documents.flatMap { document ->
            val pairs = flatten(document.topLevelValue, emptyList(), document)
            if (pairs.isEmpty()) return@flatMap emptyList()

            val profiles = pairs.declaredProfiles() ?: listOf(fileProfile)
            val first = pairs.toParsedDocument(support, psiFile, profiles.first())
            // A document may activate for several profiles at once (`on-profile: "dev|stage"`);
            // the same entries then legitimately belong to each of them. Shared with
            // PropertiesConfigParser, which needs the identical fan-out for the same reason.
            listOf(first) + profiles.drop(1).map { first.retagTo(it) }
        }
    }

    private fun flatten(
        value: YAMLValue?,
        path: List<String>,
        anchor: PsiElement,
    ): List<RawPair> = when (value) {
        is YAMLMapping ->
            if (value.keyValues.isEmpty()) {
                single(path, anchor, text = null, shape = ValueShape.MAP)
            } else {
                value.keyValues.flatMap { keyValue ->
                    // The key/value pair is the navigation anchor so the caret lands on the key.
                    flatten(keyValue.value, path + keyValue.keyText, keyValue)
                }
            }

        is YAMLSequence -> flattenSequence(value, path, anchor)

        is YAMLScalar -> single(path, anchor, value.textValue, shape = null)

        // `key:` with no value at all, which Spring treats as an empty string.
        null -> single(path, anchor, text = null, shape = null)

        else -> single(path, anchor, value.text, shape = null)
    }

    /**
     * A list of scalars is one value, not N keys.
     *
     * Splitting `hosts: [a, b]` into `hosts[0]` and `hosts[1]` meant a two-element difference
     * between environments produced six separate findings, and made `hosts: []` look like an
     * entirely different key. Collapsing to a single LIST entry lets shape comparison do the
     * right thing: LIST against LIST is consistent, LIST against STRING is real drift.
     *
     * Lists of mappings still flatten per element, because their nested keys are worth comparing
     * individually and there is no meaningful single value to collapse them to.
     */
    private fun flattenSequence(
        value: YAMLSequence,
        path: List<String>,
        anchor: PsiElement,
    ): List<RawPair> {
        val itemValues = value.items.map { it.value }
        return when {
            itemValues.isEmpty() -> single(path, anchor, text = null, shape = ValueShape.LIST)

            itemValues.all { it is YAMLScalar } -> single(
                path,
                anchor,
                text = itemValues.joinToString(",") { (it as YAMLScalar).textValue },
                shape = ValueShape.LIST,
            )

            else -> value.items.flatMapIndexed { index, item ->
                flatten(item.value, path + "[$index]", item)
            }
        }
    }

    private fun single(
        path: List<String>,
        anchor: PsiElement,
        text: String?,
        shape: ValueShape?,
    ): List<RawPair> {
        // A value at the document root carries no key and nothing to compare.
        if (path.isEmpty()) return emptyList()
        val rawKey = joinPath(path)
        return listOf(
            RawPair(
                rawKey = rawKey,
                key = KeyNormalizer.normalize(rawKey),
                text = text,
                element = anchor,
                shapeOverride = shape,
            ),
        )
    }

    private companion object {
        val YAML_EXTENSIONS = setOf("yml", "yaml")

        /** Joins segments with `.`, except index segments which attach directly: `a.b[0].c`. */
        fun joinPath(path: List<String>): String = buildString {
            for (segment in path) {
                if (isNotEmpty() && !segment.startsWith("[")) append('.')
                append(segment)
            }
        }
    }
}
