package eu.darken.butler.main.ui

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.SingleLiveEvent
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.uix.ViewModel2
import eu.darken.butler.common.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject


@HiltViewModel
class MainViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    @Suppress("unused") private val handle: SavedStateHandle,
    private val upgradeRepo: UpgradeRepo,
) : ViewModel2(dispatcherProvider = dispatcherProvider) {

    private val stateFlow = MutableStateFlow(State())
    val state = stateFlow
        .onEach { log(VERBOSE) { "New state: $it" } }
        .asLiveData2()

    val errorEvents = SingleLiveEvent<Throwable>()

    private val readyStateInternal = MutableStateFlow(true)
    val readyState = readyStateInternal.asLiveData2()

//    val keepScreenOn = taskManager.state
//        .map { !it.isIdle || BuildConfigWrap.DEBUG }
//        .asLiveData2()

    fun onGo() {
        stateFlow.value = stateFlow.value.copy(ready = true)
    }

    fun checkUpgrades() = launch {
        log(TAG) { "checkUpgrades()" }
        upgradeRepo.refresh()
    }

    data class State(
        val ready: Boolean = false
    )

    companion object {
        private val TAG = logTag("MainActivity", "ViewModel")
    }
}