package eu.darken.butler.upgrade.ui

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.combine
import eu.darken.butler.upgrade.core.UpgradeRepoFoss
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlin.time.Instant

enum class FossUpgradeView { PITCH, STATUS_FREE, STATUS_UPGRADED }

@HiltViewModel(assistedFactory = UpgradeViewModel.Factory::class)
class UpgradeViewModel @AssistedInject constructor(
    @Assisted private val manage: Boolean,
    dispatcherProvider: DispatcherProvider,
    private val savedStateHandle: SavedStateHandle,
    private val upgradeRepo: UpgradeRepoFoss,
) : ViewModel4(dispatcherProvider, logTag("Upgrade", "Screen", "VM")) {

    val snackbarEvent = SingleEventFlow<Int>()
    val toastEvent = SingleEventFlow<Int>()

    private val showUpgradeOptions = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_SHOW_OPTIONS) ?: false)

    init {
        // The manage route is the settings "upgrade status" entry — supporters must not be bounced
        // out of the screen they explicitly opened. Only the default route auto-closes once the
        // sponsorship lands, which is what DestinationUpgrade promises its callers.
        if (!manage) {
            upgradeRepo.upgradeInfo
                .filter { it.isPro }
                .take(1)
                .onEach { navUp() }
                .launchInViewModel()
        }

        // The repo settles a failed entitlement read into an error Info instead of dying, so the
        // failure has to be raised here or it would never reach the user. Deliberately NOT guarded
        // against repetition: butler refreshes the entitlement on every foreground transition, so a
        // persistently broken store re-raises this dialog on each resume while the screen is open.
        // That is the corrupt-record doctrine's honest repeated signal — nothing is destroyed,
        // recovery stays an explicit user action, and silence would be the worse failure.
        upgradeRepo.upgradeInfo
            .filter { !it.isPro && it.error != null }
            .onEach { current ->
                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                errorEvents.tryEmit(current.error!!)
            }
            .launchInViewModel()
    }

    val state = combine(
        upgradeRepo.upgradeInfo,
        showUpgradeOptions,
    ) { info, showOptions ->
        val view = when {
            // The sponsor pitch route never shows a status view.
            !manage -> FossUpgradeView.PITCH
            info.isPro -> FossUpgradeView.STATUS_UPGRADED
            showOptions -> FossUpgradeView.PITCH
            else -> FossUpgradeView.STATUS_FREE
        }
        // Derived in the same emission as the view on purpose: a sibling flow would let the
        // upgraded status render for a frame without the date it is supposed to carry.
        State(view = view, supporterSince = info.upgradedAt)
    }.asStateFlow()

    data class State(
        val view: FossUpgradeView,
        val supporterSince: Instant? = null,
    )

    fun onShowUpgradeOptions() {
        log(tag) { "onShowUpgradeOptions()" }
        savedStateHandle[KEY_SHOW_OPTIONS] = true
        showUpgradeOptions.value = true
    }

    // The pitch sponsor action: arms the 5s "visited the sponsor page" honor check.
    fun openSponsor() {
        log(tag) { "openSponsor()" }
        // Single-flight: a second tap while a launch is still awaiting its return would restamp
        // the timer and reset the window the return check evaluates.
        if (hasPendingSponsorLaunch()) {
            log(tag) { "A sponsor launch is already awaiting its return" }
            return
        }
        // Only arm the heuristic if the page actually opened; otherwise an unrelated later
        // background/foreground round-trip would grant supporter status with no page ever shown.
        if (!upgradeRepo.openGithubSponsorsPage()) {
            log(tag) { "Sponsor page didn't open; not arming the unlock heuristic" }
            return
        }
        savedStateHandle[KEY_SPONSOR_PRESSED_AT] = SystemClock.elapsedRealtime()
    }

    // The recurring-donation action shown to existing supporters: opens the sponsor page WITHOUT
    // arming the unlock heuristic (they're already unlocked; re-arming would pop a spurious snackbar).
    fun openRecurringSponsor() {
        log(tag) { "openRecurringSponsor()" }
        upgradeRepo.openGithubSponsorsPage()
    }

    /**
     * Whether a sponsor-page launch is still awaiting its return.
     *
     * Handle-backed, so it survives process recreation while the browser is in front — the screen's
     * in-memory return tracker does not, and gating on that alone drops the first return after a
     * recreation.
     */
    fun hasPendingSponsorLaunch(): Boolean = savedStateHandle.contains(KEY_SPONSOR_PRESSED_AT)

    fun checkSponsorReturn() = launch {
        val pressedAt = savedStateHandle.remove<Long>(KEY_SPONSOR_PRESSED_AT) ?: return@launch

        try {
            // Evaluated before the duration: an already-upgraded supporter has nothing left to
            // unlock, so this fast path exists for the UX — return quietly, no redundant write
            // attempt and no thanks toast for an unlock that already happened. Data integrity is
            // not this guard's job: the repo's create-only transaction owns that.
            if (upgradeRepo.upgradeInfo.first().isPro) {
                log(tag) { "checkSponsorReturn(): Already upgraded, staying quiet" }
                return@launch
            }

            // Monotonic: wall-clock elapsed can be moved by the user or a network time sync between
            // the launch and the return.
            val elapsed = SystemClock.elapsedRealtime() - pressedAt
            log(tag) { "checkSponsorReturn(): elapsed=${elapsed}ms" }

            if (elapsed < SPONSOR_DELAY_MS) {
                log(tag, WARN) { "checkSponsorReturn(): Too quick, showing snackbar" }
                snackbarEvent.emit(R.string.upgrade_screen_sponsor_too_fast)
            } else {
                log(tag, INFO) { "checkSponsorReturn(): Delay passed, persisting upgrade" }
                val created = upgradeRepo.persistUpgrade()
                if (created) {
                    toastEvent.emit(R.string.upgrade_screen_thanks_toast)
                } else {
                    // The isPro fast-path read a stale emission; the transaction kept the existing record.
                    log(tag) { "checkSponsorReturn(): Record already existed, staying quiet" }
                }
            }
        } catch (e: Exception) {
            // The marker was consumed above; neither a failed entitlement read nor a failed write may
            // eat the user's valid sponsor visit — restore it so the next return/resume can retry the
            // unlock. Conditional: the user may have armed a NEWER launch while this attempt was
            // suspended, and that one must survive. The contains-check has a small check-then-act
            // window against a concurrent new arm; accepted — the create-only transaction owns data
            // integrity, a wrong winner only changes which REAL visit's timestamp gates the unlock.
            // Rethrow unconditionally: cancellation is not swallowed.
            if (!savedStateHandle.contains(KEY_SPONSOR_PRESSED_AT)) {
                savedStateHandle[KEY_SPONSOR_PRESSED_AT] = pressedAt
            }
            throw e
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(manage: Boolean): UpgradeViewModel
    }

    companion object {
        private const val KEY_SPONSOR_PRESSED_AT = "sponsor_pressed_at"
        private const val KEY_SHOW_OPTIONS = "show_upgrade_options"
        private const val SPONSOR_DELAY_MS = 5_000L
    }
}
