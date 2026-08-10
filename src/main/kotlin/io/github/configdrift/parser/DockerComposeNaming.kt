package io.github.configdrift.parser

import io.github.configdrift.model.ProfileId

/**
 * Works out which profile a docker-compose file belongs to, by filename convention alone.
 *
 * Covers both naming families in active use: the classic `docker-compose.yml` /
 * `docker-compose.<profile>.yml`, and the newer Compose Spec `compose.yml` /
 * `compose.<profile>.yaml` (compose-spec.io's recommended name). `docker-compose.override.yml`
 * is not special-cased — it becomes its own profile named `override`, not silently merged into
 * `default`. This model doesn't attempt to represent Compose's real override-merge semantics any
 * more than [ProfileResolver] attempts to evaluate Spring's `on-profile` boolean expressions —
 * every file's declared profile is a column, and precedence between overlapping sources is a
 * question this plugin deliberately leaves to the user to judge.
 */
object DockerComposeNaming {

    private val FILENAME = Regex("""^(?:docker-)?compose(?:\.([A-Za-z0-9_-]+))?\.ya?ml$""")

    fun matches(fileName: String): Boolean = FILENAME.matches(fileName)

    fun profileFor(fileName: String): ProfileId? {
        val match = FILENAME.matchEntire(fileName) ?: return null
        val profile = match.groupValues[1]
        return if (profile.isEmpty()) ProfileId.DEFAULT else ProfileId(profile)
    }
}
