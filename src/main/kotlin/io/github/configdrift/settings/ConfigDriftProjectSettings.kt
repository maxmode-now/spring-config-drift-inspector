package io.github.configdrift.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-wide Config Drift preferences: [io.github.configdrift.engine.OverlayHeuristic] overrides
 * and dismissed findings.
 *
 * Stored as a project file (`configDrift.xml`), not the workspace file. Both kinds of preference
 * here are facts about the project rather than personal taste — whether `local` is a partial
 * overlay, or whether the team has agreed a given warning is expected — so they belong in version
 * control and should be the same for every teammate who opens the project, the same way an
 * ESLint disable comment or a SpotBugs exclude filter would be.
 *
 * Profiles and findings are keyed by plain strings (not [io.github.configdrift.model.ProfileId] /
 * [io.github.configdrift.engine.FindingFingerprint]-typed values) because a persisted XML state
 * must be a plain serializable shape.
 */
@State(name = "ConfigDriftSettings", storages = [Storage("configDrift.xml")])
@Service(Service.Level.PROJECT)
class ConfigDriftProjectSettings : PersistentStateComponent<ConfigDriftProjectSettings.State> {

    class State {
        var manualComplete: MutableSet<String> = mutableSetOf()
        var manualOverlay: MutableSet<String> = mutableSetOf()

        /** [io.github.configdrift.engine.FindingFingerprint] values the user has dismissed. */
        var suppressedFindingIds: MutableSet<String> = mutableSetOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun getInstance(project: Project): ConfigDriftProjectSettings =
            project.getService(ConfigDriftProjectSettings::class.java)
    }
}
