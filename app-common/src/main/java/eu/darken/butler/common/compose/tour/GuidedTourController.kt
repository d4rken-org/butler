package eu.darken.butler.common.compose.tour

import androidx.navigation3.runtime.NavKey
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.tour.TourPreferences
import eu.darken.butler.common.tour.TourSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GuidedTourController @Inject constructor(
    private val tourSettings: TourSettings,
    @AppScope private val scope: CoroutineScope,
) : GuidedTourAccess {

    private val _session = MutableStateFlow<TourSession?>(null)
    override val session: StateFlow<TourSession?> = _session.asStateFlow()

    @Volatile private var currentTopRoute: NavKey? = null
    @Volatile private var routeAtStart: NavKey? = null

    // Step ids of the active session that actually rendered (anchored or centerless). Reset on
    // start, filled by the host via [markStepRendered]. A tour is only persisted as "completed"
    // once every one of its steps is in here: a missing one means that step's target never
    // registered, and burning the tour on that would hide steps the user never saw.
    private val sessionRenderedStepIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Singleton-scoped, so this resets on app restart — which is exactly what "skip for now" means:
    // suppress the tour for the current process lifetime, not persistently.
    private val skippedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val mutationMutex = Mutex()

    override suspend fun shouldStart(definition: TourDefinition): Boolean {
        if (!tourSettings.isGuidedToursEnabled.value()) return false
        if (_session.value != null) return false
        val raw = definition.id.raw
        if (raw in skippedThisSession) return false
        val prefs = tourSettings.tourPreferences.value()
        return raw !in prefs.completed && raw !in prefs.dismissed
    }

    override suspend fun start(definition: TourDefinition) {
        mutationMutex.withLock { startLocked(definition) }
    }

    override suspend fun tryStart(definition: TourDefinition): Boolean = mutationMutex.withLock {
        startLocked(definition)
    }

    /**
     * Adopt [definition] into a live session for the same tour at the same steps.
     *
     * A screen that recomposes from scratch - activity recreation - hands us a definition whose
     * `prepareTarget` hooks capture that composition's state holders, while the ones the session
     * holds capture the disposed ones. Scrolling a detached `LazyGridState` is a silent no-op, so
     * the step's anchor never comes into view and the host grace-skips it. Step ids have to match
     * as well as the tour id, or [TourSession.stepIndex] would carry over onto different steps.
     */
    private suspend fun adoptRefreshedDefinition(definition: TourDefinition): Boolean {
        val live = _session.value ?: return false
        if (!live.definition.describesSameSessionAs(definition)) return false
        // Same instance: an effect re-ran inside one composition, nothing was rebuilt. Bail before
        // the prepare below, or reopening the tab manager would re-scroll the grid under the user.
        if (live.definition === definition) return true
        log(TAG, VERBOSE) { "adoptRefreshedDefinition(${definition.id.raw}) at ${live.stepIndex}" }
        // Publish only after the hook, like next() does: the host keys its missing-target grace
        // window on the session object, so a timer running through the prepare below still names the
        // old definition and next() drops it. Publishing first would restart that timer with the
        // identity the guard accepts.
        // The step already on screen needs the fresh hook too: its anchor may have gone with the old
        // composition, and a single-step tour has no later navigation that would ever run it.
        definition.steps[live.stepIndex].prepareTarget?.invoke()
        // prepareTarget can suspend long enough for a grace-skip to advance or end the session.
        val still = _session.value ?: return true
        if (!still.definition.describesSameSessionAs(definition)) return true
        _session.value = still.copy(definition = definition)
        return true
    }

    /**
     * Whether [other] is the same tour, from the same screen, at the same steps - i.e. a rebuild of
     * what this session is already running rather than a different tour or a second live instance.
     */
    private fun TourDefinition.describesSameSessionAs(other: TourDefinition): Boolean =
        id == other.id &&
            ownerKey == other.ownerKey &&
            steps.map { it.stepId } == other.steps.map { it.stepId }

    /** Publishes the session if the tour is eligible. Returns whether this call started it. */
    private suspend fun startLocked(definition: TourDefinition): Boolean {
        // Same tour still live: the caller recomposed rather than asking for a new tour.
        if (adoptRefreshedDefinition(definition)) return false
        if (!shouldStart(definition)) {
            log(TAG, VERBOSE) { "start(${definition.id.raw}): blocked by prefs or active session" }
            return false
        }
        routeAtStart = currentTopRoute
        val firstStep = definition.steps.firstOrNull()
        if (firstStep == null) {
            log(TAG) { "start(${definition.id.raw}): no steps, ignoring" }
            return false
        }
        sessionRenderedStepIds.clear()
        firstStep.prepareTarget?.invoke()
        log(TAG) { "start(${definition.id.raw})" }
        _session.value = TourSession(definition, stepIndex = 0)
        return true
    }

    /**
     * Advance past the step the request was made for.
     *
     * [fromTour] and [fromStepId] name that step, and a request that no longer matches the current
     * session is dropped: a second Next tap queues its own coroutine, and without this guard the
     * two would advance twice — on a two-step tour that shows the last step and immediately
     * completes it, persisting a tour the user never saw. Both parts of the identity are checked
     * because step ids are only unique within a definition.
     *
     * [fromDefinition] extends that identity to the build of the tour the request was made against.
     * A request raised against a definition that has since been replaced by [adoptRefreshedDefinition]
     * names a step that still matches by id, so the pair above would accept it and advance past the
     * step the adoption just restored. It has no default: a caller that cannot name the build it
     * raised its request against is exactly the unguarded case this exists to catch.
     */
    suspend fun next(fromTour: TourId, fromStepId: String, fromDefinition: TourDefinition) {
        val onComplete: (suspend () -> Unit)? = mutationMutex.withLock {
            val s = _session.value ?: return@withLock null
            if (s.definition.id != fromTour || s.currentStep.stepId != fromStepId) {
                log(TAG, VERBOSE) { "next(${fromTour.raw}/$fromStepId): no longer the current step" }
                return@withLock null
            }
            if (fromDefinition !== s.definition) {
                log(TAG, VERBOSE) { "next(${fromTour.raw}/$fromStepId): no longer the current definition" }
                return@withLock null
            }
            if (s.isLast) return@withLock completeLocked()

            val nextStep = s.definition.steps[s.stepIndex + 1]
            nextStep.prepareTarget?.invoke()
            // Re-check session: prepareTarget may have suspended long enough for cancel/complete to fire.
            val still = _session.value ?: return@withLock null
            if (still.definition.id != s.definition.id) return@withLock null
            log(TAG, VERBOSE) { "next(${s.definition.id.raw}): ${s.stepIndex} -> ${s.stepIndex + 1}" }
            _session.value = still.copy(stepIndex = still.stepIndex + 1)
            null
        }
        // Screen callbacks can animate or acquire their own locks, so never run them while holding
        // the controller mutation lock. completeLocked() has already persisted and cleared state.
        onComplete?.invoke()
    }

    /** Go back one step. No-op when already at step 0. Re-runs the destination step's prepareTarget. */
    suspend fun previous() = mutationMutex.withLock {
        val s = _session.value ?: return@withLock
        if (s.stepIndex <= 0) return@withLock
        val previousStep = s.definition.steps[s.stepIndex - 1]
        // Mirror next(): give the destination step a chance to scroll/expand its target
        // before we publish the new index, so the host doesn't grace-skip an off-screen target.
        previousStep.prepareTarget?.invoke()
        val still = _session.value ?: return@withLock
        if (still.definition.id != s.definition.id || still.stepIndex != s.stepIndex) return@withLock
        log(TAG, VERBOSE) { "previous(${s.definition.id.raw}): ${s.stepIndex} -> ${s.stepIndex - 1}" }
        _session.value = still.copy(stepIndex = still.stepIndex - 1)
    }

    /**
     * Exit the current session and suppress this tour for the rest of the app process.
     * The skip is in-memory only, so after the app is restarted the tour becomes eligible again.
     */
    override suspend fun skipForNow() = mutationMutex.withLock {
        val s = _session.value ?: return@withLock
        log(TAG) { "skipForNow(${s.definition.id.raw})" }
        skippedThisSession += s.definition.id.raw
        _session.value = null
        routeAtStart = null
    }

    /** Persistently dismiss the current tour. Won't show again until [reset] is called. */
    suspend fun dismissForever() = mutationMutex.withLock {
        val s = _session.value ?: return@withLock
        log(TAG) { "dismissForever(${s.definition.id.raw})" }
        persistDismissed(s.definition.id.raw)
        _session.value = null
        routeAtStart = null
    }

    /**
     * Flip the global master switch off — no tour starts again until the user taps "Reset guided
     * tours" in settings (which re-enables and clears per-tour state). Per-tour [TourPreferences]
     * are intentionally left untouched: [shouldStart] gates on this flag first, so the flag alone
     * suppresses everything. The session is cleared BEFORE persisting so the overlay disappears
     * immediately — the DataStore write can settle after.
     */
    suspend fun disableAllTours() = mutationMutex.withLock {
        log(TAG) { "disableAllTours() (active=${_session.value?.definition?.id?.raw})" }
        _session.value = null
        routeAtStart = null
        tourSettings.isGuidedToursEnabled.value(false)
    }

    suspend fun complete() {
        val onComplete = mutationMutex.withLock { completeLocked() }
        onComplete?.invoke()
    }

    /**
     * Signal from the host that [stepId] of [tourId]'s session has been shown (anchored or
     * centerless). Lets [completeLocked] tell "user walked the whole tour" apart from steps that
     * grace-skipped because their target never registered. The id guard rejects a late callback
     * from a previous session that already ended (e.g. tour A's render arriving after tour B start).
     */
    fun markStepRendered(tourId: TourId, stepId: String) {
        if (_session.value?.definition?.id == tourId) sessionRenderedStepIds += stepId
    }

    private suspend fun completeLocked(): (suspend () -> Unit)? {
        val s = _session.value ?: return null
        val renderedCount = sessionRenderedStepIds.size
        if (renderedCount == 0) {
            // The whole tour fell through on missing-target grace-skips without a single step ever
            // being shown (anchors not registered yet, wrong target ids, or a transient layout
            // race). Persisting "completed" would burn the tour forever for something the user
            // never saw — treat it as skip-for-now instead, so it stays eligible after a restart.
            log(TAG, WARN) { "complete(${s.definition.id.raw}): no step ever rendered, skipping instead" }
            skippedThisSession += s.definition.id.raw
            _session.value = null
            routeAtStart = null
            return null
        }
        if (renderedCount < s.definition.steps.size) {
            log(TAG, WARN) {
                "complete(${s.definition.id.raw}): only $renderedCount of ${s.definition.steps.size} " +
                    "steps rendered ($sessionRenderedStepIds), skipping instead"
            }
            skippedThisSession += s.definition.id.raw
            _session.value = null
            routeAtStart = null
            return null
        }
        log(TAG) { "complete(${s.definition.id.raw})" }
        persistCompleted(s.definition.id.raw)
        _session.value = null
        routeAtStart = null
        return s.definition.onComplete
    }

    suspend fun reset() = mutationMutex.withLock {
        log(TAG) { "reset()" }
        skippedThisSession.clear()
        tourSettings.tourPreferences.value(TourPreferences())
        // Resetting brings tours back even if the user opted out during onboarding.
        tourSettings.isGuidedToursEnabled.value(true)
    }

    fun onRouteChanged(top: NavKey?) {
        currentTopRoute = top
        val s = _session.value ?: return
        if (s.definition.clickProtection) return
        if (top != routeAtStart) {
            log(TAG, VERBOSE) { "onRouteChanged: route changed during ${s.definition.id.raw}, completing" }
            scope.launch { complete() }
        }
    }

    private suspend fun persistDismissed(rawId: String) {
        tourSettings.tourPreferences.update { current ->
            current.copy(dismissed = current.dismissed + rawId)
        }
    }

    private suspend fun persistCompleted(rawId: String) {
        tourSettings.tourPreferences.update { current ->
            current.copy(completed = current.completed + rawId)
        }
    }

    companion object {
        private val TAG = logTag("GuidedTour", "Controller")
    }
}
