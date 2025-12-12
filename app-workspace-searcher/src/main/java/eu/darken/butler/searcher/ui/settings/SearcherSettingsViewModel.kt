package eu.darken.butler.searcher.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.SearcherSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@HiltViewModel
class SearcherSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    private val searcherSettings: SearcherSettings,
    private val searchHistory: SearchHistory,
) : ViewModel4(dispatcherProvider, logTag("Searcher", "Settings")) {

    // Create a flow for history count that refreshes periodically
    private val historyCountFlow = flow {
        while (true) {
            emit(searchHistory.getHistoryCount())
            kotlinx.coroutines.delay(1000) // Refresh every second
        }
    }.onStart { emit(0) } // Start with 0 while loading

    // Combine all settings into the final state
    val state = combine(
        searcherSettings.maxSearchResults.flow,
        searcherSettings.maxHistoryItems.flow,
        searcherSettings.saveHistory.flow,
        searcherSettings.contentSearchBinaries.flow,
        historyCountFlow,
    ) { maxSearchResults, maxHistoryItems, saveHistory, contentSearchBinaries, historyCount ->
        State(
            maxSearchResults = maxSearchResults,
            maxHistoryItems = maxHistoryItems,
            saveHistory = saveHistory,
            contentSearchBinaries = contentSearchBinaries,
            currentHistoryCount = historyCount,
        )
    }.asStateFlow()

    fun updateMaxSearchResults(count: Int) = launch {
        log(tag) { "updateMaxSearchResults($count)" }
        searcherSettings.maxSearchResults.value(count)
    }

    fun updateMaxHistoryItems(count: Int) = launch {
        log(tag) { "updateMaxHistoryItems($count)" }
        searcherSettings.maxHistoryItems.value(count)
    }

    fun updateSaveHistory(enabled: Boolean) = launch {
        log(tag) { "updateSaveHistory($enabled)" }
        searcherSettings.saveHistory.value(enabled)
    }

    fun clearSearchHistory() = launch {
        log(tag) { "clearSearchHistory()" }
        searchHistory.clearHistory()
    }

    fun updateContentSearchBinaries(enabled: Boolean) = launch {
        log(tag) { "updateContentSearchBinaries($enabled)" }
        searcherSettings.contentSearchBinaries.value(enabled)
    }

    data class State(
        val maxSearchResults: Int = 1000,
        val maxHistoryItems: Int = 10,
        val saveHistory: Boolean = true,
        val contentSearchBinaries: Boolean = false,
        val currentHistoryCount: Int = 0,
    )
}
