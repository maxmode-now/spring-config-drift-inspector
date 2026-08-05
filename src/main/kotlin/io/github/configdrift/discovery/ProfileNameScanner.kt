package io.github.configdrift.discovery

import com.intellij.openapi.project.Project
import io.github.configdrift.model.ProfileId
import io.github.configdrift.parser.ProfileResolver

/**
 * A cheap, filename-only guess at what profiles a project has, for populating the settings UI
 * before the user has ever run a full analysis.
 *
 * Deliberately does not parse file contents: settings need to open instantly, and PSI parsing is
 * the expensive part of a real analysis run. The trade-off is that a profile declared only via
 * `spring.config.activate.on-profile` inside a `---` document (not in any filename) will not
 * appear here. [io.github.configdrift.ui.ConfigDriftConfigurable] falls back to this scan only
 * when no analysis has run yet; once one has, the real, complete profile list from
 * [io.github.configdrift.model.DriftReport] is used instead.
 */
object ProfileNameScanner {

    fun scan(project: Project): List<ProfileId> =
        ConfigFileDiscovery().discoverConfigFiles(project)
            .mapNotNull { ProfileResolver.fromFileName(it.name) }
            .filter { it != ProfileId.DEFAULT }
            .distinct()
            .sorted()
}
