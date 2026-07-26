package eu.darken.butler.history.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.operations.history.HistorySettings
import eu.darken.butler.workspace.core.operations.history.OperationHistoryRepo
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HistorySettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val historySettings: HistorySettings,
    private val historyRepo: OperationHistoryRepo,
    private val historyDao: OperationHistoryDao,
) : ViewModel4(dispatcherProvider, logTag("History", "Settings")) {

    val events = SingleEventFlow<Event>()

    val state = combine(
        historySettings.saveHistory.flow,
        historySettings.maxHistoryItems.flow,
        historyRepo.observeCount(),
    ) { saveHistory, maxHistoryItems, count ->
        State(
            saveHistory = saveHistory,
            maxHistoryItems = maxHistoryItems,
            currentHistoryCount = count,
        )
    }.asStateFlow()

    fun updateSaveHistory(enabled: Boolean) = launch {
        log(tag) { "updateSaveHistory($enabled)" }
        historySettings.saveHistory.value(enabled)
    }

    fun updateMaxHistoryItems(count: Int) = launch {
        log(tag) { "updateMaxHistoryItems($count)" }
        historySettings.maxHistoryItems.value(count)
        withContext(NonCancellable) {
            historyDao.trimToMax(count)
        }
    }

    fun clearHistory() = launch {
        log(tag) { "clearHistory()" }
        historyRepo.clearAll()
        events.emit(Event.HistoryCleared)
    }

    sealed interface Event {
        data object HistoryCleared : Event
    }

    data class State(
        val saveHistory: Boolean = true,
        val maxHistoryItems: Int = 200,
        val currentHistoryCount: Int = 0,
    )
}
