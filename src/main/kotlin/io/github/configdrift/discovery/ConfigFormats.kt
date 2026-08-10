package io.github.configdrift.discovery

import io.github.configdrift.parser.DotenvNaming
import io.github.configdrift.parser.ProfileResolver

/**
 * "Is this file one the plugin cares about at all", across every supported config format.
 *
 * [ProfileResolver.isConfigFileName] answers that question for Spring's `application*.yml`/
 * `.properties` convention alone, so call sites that aren't parser-specific — VFS listening,
 * inspection gating, context-menu visibility — were coupled to Spring-only naming even though
 * their own logic has nothing Spring-specific in it. This aggregator is what they should call
 * instead; adding a future format (docker-compose) means appending one more reference here, not
 * touching any of those call sites again.
 *
 * [io.github.configdrift.parser.YamlConfigParser] and
 * [io.github.configdrift.parser.PropertiesConfigParser] deliberately keep calling
 * [ProfileResolver.isConfigFileName] directly rather than this — they should only ever match
 * Spring files, not every format the plugin understands.
 */
object ConfigFormats {

    private val matchers: List<(String) -> Boolean> = listOf(
        ProfileResolver::isConfigFileName,
        DotenvNaming::matches,
    )

    fun isKnownConfigFile(fileName: String): Boolean = matchers.any { it(fileName) }
}
