package eu.darken.butler.upgrade.ui

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.core.UpgradeRepoFoss
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class UpgradeViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val savedStateHandle: SavedStateHandle,
    private val upgradeRepo: UpgradeRepoFoss,
) : ViewModel4(dispatcherProvider, logTag("Upgrade", "Screen", "VM")) {

    val snackbarEvent = SingleEventFlow<Int>()

    fun openSponsor() {
        log(tag) { "openSponsor()" }
        savedStateHandle[KEY_SPONSOR_OPENED_AT] = Clock.System.now().toEpochMilliseconds()
        upgradeRepo.openSponsorPage()
    }

    fun onAppResumed() = launch {
        val openedAtMillis = savedStateHandle.get<Long>(KEY_SPONSOR_OPENED_AT) ?: return@launch
        savedStateHandle[KEY_SPONSOR_OPENED_AT] = null

        val elapsed = Clock.System.now() - kotlin.time.Instant.fromEpochMilliseconds(openedAtMillis)

        if (elapsed >= MINIMUM_VISIT_DURATION) {
            log(tag, INFO) { "Sponsor page visited for ${elapsed}, applying upgrade" }
            upgradeRepo.applyUpgrade()
            upgradeRepo.upgradeInfo.filter { it.isUpgraded }.first()
            navUp()
        } else {
            log(tag, WARN) { "Sponsor page visited for only ${elapsed}, too fast" }
            snackbarEvent.emit(R.string.upgrade_screen_sponsor_too_fast)
        }
    }

    companion object {
        private const val KEY_SPONSOR_OPENED_AT = "sponsor_opened_at"
        private val MINIMUM_VISIT_DURATION = 5.seconds
    }
}
