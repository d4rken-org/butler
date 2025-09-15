package eu.darken.butler.searcher.ui.search

import android.content.Context
import android.os.Environment
import androidx.compose.ui.text.input.TextFieldValue
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.searcher.core.SearchEngine
import eu.darken.butler.searcher.core.SearchHistory
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.permissions.PathPermissionChecker
import eu.darken.butler.workspace.core.permissions.PermissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = SearcherWorkspaceViewModel.Factory::class)
class SearcherWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val appContext: Context,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val searchEngine: SearchEngine,
    private val searchHistory: SearchHistory,
    private val searcherSettings: SearcherSettings,
    private val pathPermissionChecker: PathPermissionChecker,
) : ViewModel4(dispatchers, logTag("Searcher", "Workspace", id.shortTag, "Page"), navCtrl) {

    private val searchQuery = MutableStateFlow(TextFieldValue(""))
    private val currentFilter = MutableStateFlow(SearchQuery.Filter())
    private val searchPath = MutableStateFlow<APath>(LocalPath.build(Environment.getExternalStorageDirectory()))

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

        vmScope.launch {
            val defaultPath = searcherSettings.defaultSearchPath.value()
            searchPath.value = defaultPath ?: LocalPath.build(Environment.getExternalStorageDirectory())
        }
    }

    private var activeSearchJob: Job? = null
    private var currentSearchId: String? = null


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

    private val searchState = MutableStateFlow(SearchState())

    val state = combine(
        searchQuery,
        searchState,
        searcherSettings.maxHistoryItems.flow.flatMapLatest { searchHistory.getSearches(it) },
        currentFilter,
        searchPath,
    ) { values ->
        val query = values[0] as TextFieldValue
        val searchState = values[1] as SearchState
        val history = values[2] as List<SearchHistory.SearchHistoryItem>
        val filter = values[3] as SearchQuery.Filter
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
            permissionState = pathPermissionChecker.check(path),
        )
    }.asStateFlow()

    fun updateSearchQuery(query: TextFieldValue) {
        log(TAG, INFO) { "Updating search query: ${query.text}" }
        searchQuery.value = query
    }

    fun performExplicitSearch() {
        log(TAG, INFO) { "Performing explicit search with history save" }
        performSearch(saveToHistory = true)
    }

    fun performSearch(saveToHistory: Boolean = false) {
        val query = searchQuery.value.text
        if (query.isBlank()) return

        log(TAG, INFO) { "Performing search: $query" }

        activeSearchJob?.cancel()

        // Clear previous results and set searching state
        searchState.update {
            it.copy(status = SearchState.Status.SEARCHING, results = emptyList(), error = null)
        }

        // Start the search
        activeSearchJob = vmScope.launch {
            val searchRequest = SearchQuery(
                query = query,
                path = searchPath.value,
                options = SearchQuery.Options(
                    maxResults = searcherSettings.maxSearchResults.value()
                ),
                filter = currentFilter.value
            )

            // Record search in history only if explicitly requested
            currentSearchId = if (saveToHistory) {
                searchHistory.addSearch(searchRequest)
            } else {
                null
            }
            try {
                val results = mutableListOf<SearchResult>()
                searchEngine.search(
                    searchQuery = searchRequest,
                    onProgress = { progress ->
                        searchState.update { it.copy(progress = progress) }
                    }
                ).collect { result ->
                    results.add(result)
                    searchState.update { state ->
                        state.copy(results = state.results + result)
                    }
                    log(TAG) { "Search result: ${result.path}" }
                }

                // Update history with result count
                currentSearchId?.let { id ->
                    searchHistory.updateResultCount(id, results.size)
                }

                searchState.update { it.copy(status = SearchState.Status.COMPLETED) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                searchState.update { it.copy(status = SearchState.Status.CANCELLED) }
                throw e
            } catch (e: Exception) {
                log(TAG) { "Search error: $e" }
                searchState.update { it.copy(status = SearchState.Status.ERROR, error = e) }
            }
        }
    }

    fun cancelSearch() {
        log(TAG) { "Cancelling search" }
        activeSearchJob?.cancel()
        searchState.update { it.copy(status = SearchState.Status.CANCELLED) }
    }

    fun clearResults() {
        log(TAG) { "Clearing search results" }
        searchState.update {
            it.copy(
                status = SearchState.Status.IDLE,
                results = emptyList(),
                progress = null,
                error = null
            )
        }
        searchQuery.value = TextFieldValue("")
    }

    fun updateFilter(filter: SearchQuery.Filter) {
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
        vmScope.launch {
            searchHistory.clearHistory()
        }
    }

    fun removeHistoryItem(item: SearchHistory.SearchHistoryItem) {
        vmScope.launch {
            searchHistory.removeItem(item.id)
        }
    }

    fun onSearchResultClick(result: SearchResult) {
        log(TAG) { "Search result clicked: ${result.path}" }

        // Save current search to history since user found it useful
        vmScope.launch {
            val currentQuery = searchQuery.value.text
            if (currentQuery.isNotBlank()) {
                val searchRequest = SearchQuery(
                    query = currentQuery,
                    path = searchPath.value,
                    options = SearchQuery.Options(
                        maxResults = searcherSettings.maxSearchResults.value()
                    ),
                    filter = currentFilter.value
                )
                searchHistory.addSearch(searchRequest)
            }
        }

        // TODO: Navigate to explorer with the selected file
    }

    data class State(
        val id: Workspace.Id,
        val searchQuery: TextFieldValue = TextFieldValue(""),
        val searchState: SearchState = SearchState(),
        val searchHistory: List<SearchHistory.SearchHistoryItem> = emptyList(),
        val currentFilter: SearchQuery.Filter = SearchQuery.Filter(),
        val searchPath: APath,
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
        val permissionState: PermissionState = PermissionState(),
    ) {
        val isSearching: Boolean
            get() = searchState.status == SearchState.Status.SEARCHING

        val hasResults: Boolean
            get() = searchState.results.isNotEmpty()

        val needsPermissions: Boolean
            get() = permissionState.needsPermissions
    }

    fun navigateToSetup() = launch {
        log(tag) { "navigateToSetup(): Opening setup for storage permissions" }
        navTo(
            Nav.Main.destSetup(
                typeFilter = setOf(SetupModule.Type.STORAGE),
                requiredTypes = setOf(SetupModule.Type.STORAGE),
                autoCloseWhenComplete = true,
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SearcherWorkspaceViewModel
    }

    companion object {
        private val TAG = logTag("Searcher", "Workspace", "ViewModel")
    }
}
