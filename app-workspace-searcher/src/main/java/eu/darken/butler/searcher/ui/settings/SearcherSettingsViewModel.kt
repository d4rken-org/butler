package eu.darken.butler.searcher.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.searcher.core.SearcherSettings
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class SearcherSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val searcherSettings: SearcherSettings,
) : ViewModel4(dispatcherProvider, logTag("Searcher", "Settings"), navCtrl) {

    // Combine all settings into the final state
    val state = combine(
        searcherSettings.caseSensitive.flow,
        searcherSettings.wholeWord.flow,
        searcherSettings.useRegex.flow,
        searcherSettings.maxHistoryItems.flow,
        searcherSettings.saveHistory.flow,
    ) { caseSensitive, wholeWord, useRegex, maxHistoryItems, saveHistory ->
        State(
            caseSensitive = caseSensitive,
            wholeWord = wholeWord,
            useRegex = useRegex,
            maxHistoryItems = maxHistoryItems,
            saveHistory = saveHistory,
        )
    }.asStateFlow()

    fun updateCaseSensitive(enabled: Boolean) = launch {
        log(tag) { "updateCaseSensitive($enabled)" }
        searcherSettings.caseSensitive.value(enabled)
    }

    fun updateWholeWord(enabled: Boolean) = launch {
        log(tag) { "updateWholeWord($enabled)" }
        searcherSettings.wholeWord.value(enabled)
    }

    fun updateUseRegex(enabled: Boolean) = launch {
        log(tag) { "updateUseRegex($enabled)" }
        searcherSettings.useRegex.value(enabled)
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
        // Implementation would depend on how search history is stored
        // This is just a placeholder for the functionality
    }


    data class State(
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
        val maxHistoryItems: Int = 10,
        val saveHistory: Boolean = true,
    )
}
