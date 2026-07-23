package eu.darken.butler.upgrade.ui

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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

enum class FossUpgradeView { PITCH, STATUS_FREE, STATUS_UPGRADED }

@HiltViewModel(assistedFactory = UpgradeViewModel.Factory::class)
class UpgradeViewModel @AssistedInject constructor(
    @Assisted private val manage: Boolean,
    dispatcherProvider: DispatcherProvider,
    private val savedStateHandle: SavedStateHandle,
    private val upgradeRepo: UpgradeRepoFoss,
) : ViewModel4(dispatcherProvider, logTag("Upgrade", "Screen", "VM")) {

    val snackbarEvent = SingleEventFlow<Int>()

    private val showUpgradeOptions = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_SHOW_OPTIONS) ?: false)

    val state = combine(
        upgradeRepo.upgradeInfo,
        showUpgradeOptions,
    ) { info, showOptions ->
        when {
            // The sponsor pitch route never shows a status view.
            !manage -> FossUpgradeView.PITCH
            info.isUpgraded -> FossUpgradeView.STATUS_UPGRADED
            showOptions -> FossUpgradeView.PITCH
            else -> FossUpgradeView.STATUS_FREE
        }
    }.asStateFlow()

    fun onShowUpgradeOptions() {
        log(tag) { "onShowUpgradeOptions()" }
        savedStateHandle[KEY_SHOW_OPTIONS] = true
        showUpgradeOptions.value = true
    }

    // The pitch sponsor action: arms the 5s "visited the sponsor page" honor check.
    fun openSponsor() {
        log(tag) { "openSponsor()" }
        savedStateHandle[KEY_SPONSOR_OPENED_AT] = Clock.System.now().toEpochMilliseconds()
        upgradeRepo.openSponsorPage()
    }

    // The recurring-donation action shown to existing supporters: opens the sponsor page WITHOUT
    // arming the unlock heuristic (they're already unlocked; re-arming would pop a spurious snackbar).
    fun openRecurringSponsor() {
        log(tag) { "openRecurringSponsor()" }
        upgradeRepo.openSponsorPage()
    }

    fun onAppResumed() = launch {
        val openedAtMillis = savedStateHandle.get<Long>(KEY_SPONSOR_OPENED_AT) ?: return@launch
        savedStateHandle[KEY_SPONSOR_OPENED_AT] = null

        // Never nudge an already-upgraded supporter who happened to revisit the sponsor page.
        if (upgradeRepo.upgradeInfo.first().isUpgraded) {
            log(tag) { "Already upgraded on resume, skipping honor check" }
            return@launch
        }

        val elapsed = Clock.System.now() - kotlin.time.Instant.fromEpochMilliseconds(openedAtMillis)
        if (elapsed >= MINIMUM_VISIT_DURATION) {
            log(tag, INFO) { "Sponsor page visited for $elapsed, applying upgrade" }
            upgradeRepo.applyUpgrade()
            upgradeRepo.upgradeInfo.filter { it.isUpgraded }.first()
            navUp()
        } else {
            log(tag, WARN) { "Sponsor page visited for only $elapsed, too fast" }
            snackbarEvent.emit(R.string.upgrade_screen_sponsor_too_fast)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(manage: Boolean): UpgradeViewModel
    }

    companion object {
        private const val KEY_SPONSOR_OPENED_AT = "sponsor_opened_at"
        private const val KEY_SHOW_OPTIONS = "show_upgrade_options"
        private val MINIMUM_VISIT_DURATION = 5.seconds
    }
}
