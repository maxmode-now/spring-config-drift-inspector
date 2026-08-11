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
 *
 * The [State.manualComplete] / [State.manualOverlay] / [State.suppressedFindingIds] sets are
 * plain `MutableSet`s, mutated on the EDT (Settings|Apply, the suppress/un-suppress actions) and
 * read while an analysis runs on a background `Task.Backgroundable` thread
 * ([io.github.configdrift.engine.AnalysisContext.overlayByDomain],
 * [io.github.configdrift.ConfigDriftService]'s suppression filtering). Neither `LinkedHashSet`
 * (Kotlin's `mutableSetOf()`) nor `HashSet` tolerates a write on one thread racing a read on
 * another — the practical failure mode is a `ConcurrentModificationException` thrown out of the
 * analysis, though silent corruption of the set itself is possible too. All access from outside
 * this class must go through the synchronized methods below rather than touching [State]'s
 * fields directly, which is what actually closes that gap: the fields stay plain, ordinary
 * `MutableSet`s (so XML (de)serialization needs nothing special) and every read gets a private,
 * unshared copy instead of a live reference into whatever the EDT might be mutating.
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

    private val lock = Any()
    private var myState = State()

    // Required by PersistentStateComponent for the platform's own (de)serialization; plugin code
    // must not call this directly — use the synchronized accessors below instead. Returns a fresh
    // copy, not the live myState: the platform reflects over whatever this returns to write XML
    // at a time this class doesn't control, after the lock below has already been released, so a
    // live reference would still be exposed to the same race this class exists to close.
    override fun getState(): State = synchronized(lock) {
        State().also {
            it.manualComplete = myState.manualComplete.toMutableSet()
            it.manualOverlay = myState.manualOverlay.toMutableSet()
            it.suppressedFindingIds = myState.suppressedFindingIds.toMutableSet()
        }
    }

    override fun loadState(state: State) {
        synchronized(lock) { XmlSerializerUtil.copyBean(state, myState) }
    }

    /** A private, unshared copy — safe to iterate from a background analysis thread. */
    fun manualClassification(): OverlayClassification = synchronized(lock) {
        OverlayClassification(myState.manualComplete.toSet(), myState.manualOverlay.toSet())
    }

    fun overlayOverrides(): io.github.configdrift.engine.OverlayOverrides = synchronized(lock) {
        io.github.configdrift.engine.OverlayOverrides(
            manualComplete = myState.manualComplete.toSet(),
            manualOverlay = myState.manualOverlay.toSet(),
        )
    }

    fun setManualClassification(complete: Set<String>, overlay: Set<String>) = synchronized(lock) {
        myState.manualComplete = complete.toMutableSet()
        myState.manualOverlay = overlay.toMutableSet()
    }

    /** A private, unshared copy — safe to iterate from a background analysis thread. */
    fun suppressedFindingIds(): Set<String> = synchronized(lock) {
        myState.suppressedFindingIds.toSet()
    }

    /** Runs [mutate] against the live set under the same lock every read above uses. */
    fun mutateSuppressedFindingIds(mutate: (MutableSet<String>) -> Unit) = synchronized(lock) {
        mutate(myState.suppressedFindingIds)
    }

    companion object {
        fun getInstance(project: Project): ConfigDriftProjectSettings =
            project.getService(ConfigDriftProjectSettings::class.java)
    }
}

data class OverlayClassification(val manualComplete: Set<String>, val manualOverlay: Set<String>)
