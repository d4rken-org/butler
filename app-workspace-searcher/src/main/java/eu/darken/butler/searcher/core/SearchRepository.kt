package eu.darken.butler.searcher.core

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val searchEngine: SearchEngine,
    private val searcherSettings: SearcherSettings
) {
    
    data class SearchState(
        val status: Status = Status.IDLE,
        val results: List<SearchResult> = emptyList(),
        val progress: SearchEngine.SearchProgress? = null,
        val error: Exception? = null
    ) {
        enum class Status {
            IDLE, SEARCHING, COMPLETED, ERROR, CANCELLED
        }
    }
    
    @Serializable
    data class SearchHistoryItem(
        val query: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    
    private val _searchHistory = MutableStateFlow<List<SearchHistoryItem>>(emptyList())
    val searchHistory: StateFlow<List<SearchHistoryItem>> = _searchHistory.asStateFlow()
    
    private var currentSearchJob: Job? = null
    
    init {
        appScope.launch {
            searcherSettings.searchHistory.flow.onEach { history ->
                _searchHistory.value = history
            }.launchIn(this)
        }
    }
    
    fun startSearch(
        query: String,
        startPath: APath,
        filter: SearchFilter = SearchFilter.EMPTY,
        searchContent: Boolean = false
    ): Flow<SearchResult> {
        log(TAG, INFO) { "Starting search: query='$query', path=$startPath" }
        
        // Cancel any ongoing search
        currentSearchJob?.cancel()
        
        // Clear previous results
        _state.update { it.copy(status = SearchState.Status.SEARCHING, results = emptyList(), error = null) }
        
        // Save to history
        appScope.launch {
            val history = _searchHistory.value.toMutableList()
            history.removeAll { it.query == query }
            history.add(0, SearchHistoryItem(query))
            if (history.size > MAX_HISTORY_SIZE) {
                history.removeAt(history.lastIndex)
            }
            searcherSettings.searchHistory.update { history }
        }
        
        return flow {
            val maxResults = searcherSettings.maxSearchResults.flow.first()
            
            val searchOptions = SearchEngine.SearchOptions(
                query = query,
                startPath = startPath,
                filter = filter,
                searchContent = searchContent,
                maxResults = maxResults
            )
            
            searchEngine.search(
                options = searchOptions,
                onProgress = { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            ).collect { emit(it) }
        }.onStart {
            _state.update { it.copy(status = SearchState.Status.SEARCHING) }
        }.onEach { result ->
            _state.update { state ->
                state.copy(results = state.results + result)
            }
        }.onCompletion { throwable ->
            _state.update { state ->
                when {
                    throwable == null -> state.copy(status = SearchState.Status.COMPLETED)
                    throwable is kotlinx.coroutines.CancellationException -> state.copy(status = SearchState.Status.CANCELLED)
                    else -> state.copy(status = SearchState.Status.ERROR, error = throwable as? Exception)
                }
            }
        }.catch { e ->
            log(TAG) { "Search error: $e" }
            _state.update { it.copy(status = SearchState.Status.ERROR, error = e as? Exception) }
        }
    }
    
    fun cancelSearch() {
        log(TAG) { "Cancelling search" }
        currentSearchJob?.cancel()
        _state.update { it.copy(status = SearchState.Status.CANCELLED) }
    }
    
    fun clearHistory() {
        appScope.launch {
            searcherSettings.searchHistory.update { emptyList() }
        }
    }
    
    fun removeFromHistory(item: SearchHistoryItem) {
        appScope.launch {
            val history = _searchHistory.value.toMutableList()
            history.remove(item)
            searcherSettings.searchHistory.update { history }
        }
    }
    
    companion object {
        private val TAG = logTag("Searcher", "Repository")
        private const val MAX_HISTORY_SIZE = 20
    }
}