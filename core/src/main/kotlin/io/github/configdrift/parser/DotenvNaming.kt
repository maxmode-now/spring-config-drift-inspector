package io.github.configdrift.parser

import io.github.configdrift.model.ProfileId

/**
 * Works out which profile a `.env` file belongs to, by filename convention alone.
 *
 * `.env` is the default profile; `.env.<name>` (e.g. `.env.production`) is profile `<name>`. A
 * trailing `.local` is a personal-override suffix, not a profile of its own — it is stripped
 * before matching, so `.env.local` and `.env.production.local` merge into `default` and
 * `production` respectively via the same last-wins grouping [ConfigDriftService][io.github.configdrift.ConfigDriftService]
 * already uses when one profile is fed by several YAML `---` documents.
 *
 * Known limitation: real dotenv tooling treats a bare `.env.local` as applying to *every*
 * environment, not just `default`. This model has no way to express "applies to all profiles",
 * so that nuance is lost — the same kind of simplification [ProfileResolver]'s `on-profile`
 * boolean-expression handling already accepts for Spring.
 */
object DotenvNaming {

    private val FILENAME = Regex("""^\.env(?:\.([A-Za-z0-9_-]+))?$""")

    fun matches(fileName: String): Boolean = FILENAME.matches(stripLocalSuffix(fileName))

    fun profileFor(fileName: String): ProfileId? {
        val match = FILENAME.matchEntire(stripLocalSuffix(fileName)) ?: return null
        val profile = match.groupValues[1]
        return if (profile.isEmpty()) ProfileId.DEFAULT else ProfileId(profile)
    }

    private fun stripLocalSuffix(fileName: String): String = fileName.removeSuffix(".local")
}
