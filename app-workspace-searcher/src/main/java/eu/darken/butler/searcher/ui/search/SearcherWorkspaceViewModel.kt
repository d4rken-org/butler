package eu.darken.butler.searcher.ui.search

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.searcher.core.SearchFilter
import eu.darken.butler.searcher.core.SearchRepository
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.io.File

@HiltViewModel(assistedFactory = SearcherWorkspaceViewModel.Factory::class)
class SearcherWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val searchRepository: SearchRepository,
    private val searcherSettings: SearcherSettings,
) : ViewModel4(dispatchers, logTag("Workspace", "Searcher", id.shortTag, "Page"), navCtrl) {
    
    private val searchQuery = MutableStateFlow("")
    private val currentFilter = MutableStateFlow(SearchFilter.EMPTY)
    private val searchPath = MutableStateFlow<APath>(LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler"))
    
    init {
        combine(
            searcherSettings.caseSensitive.flow,
            searcherSettings.wholeWord.flow,
            searcherSettings.useRegex.flow,
        ) { caseSensitive, wholeWord, useRegex ->
            currentFilter.value = currentFilter.value.copy(
                caseSensitive = caseSensitive,
                wholeWord = wholeWord,
                useRegex = useRegex
            )
        }.launchIn(vmScope)
    }
    
    private var activeSearchJob: Job? = null

    val state = combine(
        searchQuery,
        searchRepository.state,
        searchRepository.searchHistory,
        currentFilter,
        searchPath,
    ) { values ->
        val query = values[0] as String
        val searchState = values[1] as SearchRepository.SearchState
        val history = values[2] as List<SearchRepository.SearchHistoryItem>
        val filter = values[3] as SearchFilter
        val path = values[4] as APath
        
        State(
            id = id,
            searchQuery = query,
            searchState = searchState,
            searchHistory = history,
            currentFilter = filter,
            searchPath = path,
            caseSensitive = filter.caseSensitive,
            wholeWord = filter.wholeWord,
            useRegex = filter.useRegex,
        )
    }.asStateFlow()
    
    fun updateSearchQuery(query: String) {
        log(TAG, INFO) { "Updating search query: $query" }
        searchQuery.value = query
    }
    
    fun performSearch() {
        val query = searchQuery.value
        if (query.isBlank()) return
        
        log(TAG, INFO) { "Performing search: $query" }
        
        activeSearchJob?.cancel()
        activeSearchJob = searchRepository.startSearch(
            query = query,
            startPath = searchPath.value,
            filter = currentFilter.value
        ).onEach { result ->
            log(TAG) { "Search result: ${result.path}" }
        }.launchIn(vmScope)
    }
    
    fun cancelSearch() {
        log(TAG) { "Cancelling search" }
        searchRepository.cancelSearch()
        activeSearchJob?.cancel()
    }
    
    fun updateFilter(filter: SearchFilter) {
        log(TAG) { "Updating filter: $filter" }
        currentFilter.value = filter
    }
    
    fun toggleCaseSensitive() {
        vmScope.launch {
            val current = searcherSettings.caseSensitive.flow.first()
            searcherSettings.caseSensitive.update { !current }
        }
    }
    
    fun toggleWholeWord() {
        vmScope.launch {
            val current = searcherSettings.wholeWord.flow.first()
            searcherSettings.wholeWord.update { !current }
        }
    }
    
    fun toggleRegex() {
        vmScope.launch {
            val current = searcherSettings.useRegex.flow.first()
            searcherSettings.useRegex.update { !current }
        }
    }
    
    fun updateSearchPath(path: APath) {
        log(TAG) { "Updating search path: $path" }
        searchPath.value = path
    }
    
    fun clearSearchHistory() {
        searchRepository.clearHistory()
    }
    
    fun removeHistoryItem(item: SearchRepository.SearchHistoryItem) {
        searchRepository.removeFromHistory(item)
    }
    
    fun onSearchResultClick(result: SearchResult) {
        log(TAG) { "Search result clicked: ${result.path}" }
        // TODO: Navigate to explorer with the selected file
    }

    data class State(
        val id: Workspace.Id,
        val searchQuery: String = "",
        val searchState: SearchRepository.SearchState = SearchRepository.SearchState(),
        val searchHistory: List<SearchRepository.SearchHistoryItem> = emptyList(),
        val currentFilter: SearchFilter = SearchFilter.EMPTY,
        val searchPath: APath,
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
    ) {
        val isSearching: Boolean
            get() = searchState.status == SearchRepository.SearchState.Status.SEARCHING
            
        val hasResults: Boolean
            get() = searchState.results.isNotEmpty()
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SearcherWorkspaceViewModel
    }
    
    companion object {
        private val TAG = logTag("Workspace", "Searcher", "ViewModel")
    }
}
