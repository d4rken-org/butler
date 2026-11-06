package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.hidden.ExplorerHiddenPathsRepo
import eu.darken.butler.explorer.core.hidden.HideFeedback
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.isProForUi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hide and unhide for the selection bar and the file options sheet.
 *
 * Hiding is Pro, unhiding is not: an entry hidden during a trial must stay recoverable without
 * paying. A hide raises a transient bar (latest-wins, id-guarded timeout like the favorites bar);
 * when the tab shows hidden entries the hidden ones stay on screen, so the bar offers to stop that.
 */
class ExplorerHiddenPathsController(
    private val hiddenPathsRepo: ExplorerHiddenPathsRepo,
    private val upgradeRepo: UpgradeRepo,
    private val scope: CoroutineScope,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val isPickerActive: () -> Boolean,
    private val isShowingHidden: () -> Boolean,
    private val stopShowingHidden: () -> Unit,
    private val navToUpgrade: () -> Unit,
    private val tag: String,
) {

    private val feedbackFlow = MutableStateFlow<HideFeedback?>(null)
    val feedback: StateFlow<HideFeedback?> = feedbackFlow
    private var timeoutJob: Job? = null
    private var timeoutFeedbackId: Long? = null
    private val feedbackIdGen = AtomicLong(0L)

    // Taps while billing connects would otherwise each queue their own upgrade screen.
    private val hideInFlight = AtomicBoolean(false)

    /**
     * Put [paths] on the hide list, or open the upgrade screen for a free user.
     *
     * @param onHidden runs once the paths are stored, not when the gate routed to the upgrade screen.
     */
    fun hide(paths: List<APath<*>>, onHidden: () -> Unit = {}) {
        log(tag) { "hide(${paths.map { it.path }})" }
        if (suppressedByPicker("hide")) return
        if (!hideInFlight.compareAndSet(false, true)) {
            log(tag) { "hide(): a previous hide is still waiting, ignoring" }
            return
        }
        doLaunch {
            try {
                if (!upgradeRepo.isProForUi()) {
                    log(tag, INFO) { "Hiding is Pro-only, routing to the upgrade screen" }
                    navToUpgrade()
                    return@doLaunch
                }
                val showHiddenOn = isShowingHidden()
                supersedePending()

                val added = hiddenPathsRepo.hideAll(paths)
                onHidden()
                if (added.isEmpty()) {
                    log(tag) { "hide(): every path was already hidden, nothing to confirm" }
                    // The superseded feedback's timeout is gone, so clear it explicitly.
                    feedbackFlow.value = null
                    return@doLaunch
                }

                publish { id ->
                    HideFeedback(
                        id = id,
                        displayName = added.first().userReadableName,
                        count = added.size,
                        showHiddenOn = showHiddenOn,
                    )
                }
            } finally {
                hideInFlight.set(false)
            }
        }
    }

    /** Take [paths] off the hide list. Ends any pending hide feedback, which may name these paths. */
    fun unhide(paths: List<APath<*>>) = doLaunch {
        log(tag) { "unhide(${paths.map { it.path }})" }
        if (suppressedByPicker("unhide")) return@doLaunch
        clearFeedback()
        hiddenPathsRepo.unhideAll(paths)
    }

    /** Stop showing hidden entries in this tab, which the bar only offers while they are shown. */
    fun onFeedbackAction() = doLaunch {
        val current = feedbackFlow.value ?: return@doLaunch
        log(tag) { "onFeedbackAction(): showHiddenOn=${current.showHiddenOn}" }
        cancelTimeout(onlyFor = current.id)
        clearIfCurrent(current.id)
        if (current.showHiddenOn) stopShowingHidden()
    }

    /** Drop pending feedback without acting on it, e.g. when the picker becomes active. */
    fun clearFeedback() {
        if (feedbackFlow.value == null) return
        supersedePending()
        feedbackFlow.value = null
    }

    private fun suppressedByPicker(caller: String): Boolean {
        if (!isPickerActive()) return false
        log(tag, WARN) { "$caller called in picker mode; ignoring" }
        return true
    }

    private fun supersedePending() = cancelTimeout()

    private fun cancelTimeout(onlyFor: Long? = null) {
        if (onlyFor != null && timeoutFeedbackId != onlyFor) return
        timeoutJob?.cancel()
        timeoutJob = null
        timeoutFeedbackId = null
    }

    private fun publish(build: (Long) -> HideFeedback) {
        val id = feedbackIdGen.incrementAndGet()
        feedbackFlow.value = build(id)
        timeoutFeedbackId = id
        timeoutJob = scope.launch {
            delay(FEEDBACK_TIMEOUT)
            clearIfCurrent(id)
        }
    }

    private fun clearIfCurrent(id: Long) {
        feedbackFlow.update { current -> if (current?.id == id) null else current }
    }

    companion object {
        internal val FEEDBACK_TIMEOUT = 5.seconds
    }
}
