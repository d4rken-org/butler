package eu.darken.butler.searcher.ui.search

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.FileProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.commonParent
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import kotlinx.coroutines.flow.combine as kotlinxCombine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.searcher.core.SearchEngine
import eu.darken.butler.searcher.core.SearchHistory
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogEvent
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogState
import eu.darken.butler.searcher.ui.search.rows.FileRowData
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.explorer.core.arguments.ExternalExplorerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.get
import eu.darken.butler.workspace.core.permissions.PathPermissionCheck
import eu.darken.butler.workspace.core.permissions.PermissionState
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

@HiltViewModel(assistedFactory = SearcherWorkspaceViewModel.Factory::class)
class SearcherWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val appContext: Context,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val searchEngine: SearchEngine,
    private val searchHistory: SearchHistory,
    private val searcherSettings: SearcherSettings,
    private val pathPermissionCheck: PathPermissionCheck,
    private val clipboardRepo: ClipboardRepo,
    private val operationsManager: OperationsManager,
    private val workspaceRemote: WorkspaceRemote,
    private val workspaceProvider: WorkspaceProvider,
    private val systemClipboardHelper: SystemClipboardHelper,
) : ViewModel4(dispatchers, logTag("Searcher", "Workspace", id.shortTag, "Page"), navCtrl) {

    private val workspaceSource: Flow<SearcherWorkspace?> =
        workspaceProvider.retrieve(id).map { workspace: Workspace? -> workspace as? SearcherWorkspace }
    private suspend fun getWorkspace(): SearcherWorkspace = workspaceSource.filterNotNull().first()

    private val searchQuery = MutableStateFlow(TextFieldValue(""))
    private val currentFilter = MutableStateFlow(SearchQuery.Filter())
    private val searchTargets = MutableStateFlow<List<SearchTarget>>(emptyList())
    private val selectionState = MutableStateFlow(SearcherSelectionState())
    private val quickActionsResult = MutableStateFlow<SearchResult?>(null)
    private val dialogStateFlow = MutableStateFlow<SearcherDialogState>(SearcherDialogState.None)

    val dialogEvents = SingleEventFlow<SearcherDialogEvent>()

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
            val defaultTargets = searcherSettings.defaultSearchTargets.value()
            searchTargets.value = defaultTargets.ifEmpty {
                listOf(SearchTarget.Path.from(LocalPath.build(Environment.getExternalStorageDirectory())))
            }
        }

        // Handle dialog events
        dialogEvents
            .onEach { event -> handleDialogEvent(event) }
            .launchIn(vmScope)

        // Listen for picker results
        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag, INFO) { "Received picker result: ${result.selectedPaths}" }
                if (result.selectedPaths.isNotEmpty()) {
                    // Append new paths to existing targets, removing duplicates by path
                    val newTargets = result.selectedPaths.map { SearchTarget.Path.from(it) }
                    val existingPaths = searchTargets.value.filterIsInstance<SearchTarget.Path>().map { it.path }.toSet()
                    val uniqueNewTargets = newTargets.filter { it.path !in existingPaths }
                    val updatedTargets = searchTargets.value + uniqueNewTargets
                    searchTargets.value = updatedTargets
                    searcherSettings.defaultSearchTargets.value(updatedTargets)
                }
            }
            .launchIn(vmScope)
    }

    private var activeSearchJob: Job? = null
    private var currentSearchId: String? = null
    private var currentSearchParams: SearchQuery? = null


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

    data class ClipboardState(
        val entries: List<ClipboardClip> = emptyList(),
    )

    val clipboard = clipboardRepo.state
        .map { repoState -> ClipboardState(entries = repoState.entries) }
        .asStateFlow()

    data class OperationsState(
        val operations: List<OperationDisplay> = emptyList(),
    )

    val operations = workspaceSource
        .filterNotNull()
        .flatMapLatest { it.operations }
        .map { opsState ->
            val ops = opsState.operations
                .map { it.toDisplayModel() }
                .sortedWith(
                    compareBy<OperationDisplay> { op ->
                        // Priority: Running > Waiting > Queued > Others
                        when (op.state) {
                            is OperationDisplay.State.Running -> 0
                            is OperationDisplay.State.Waiting -> 1
                            is OperationDisplay.State.Queued -> 2
                            is OperationDisplay.State.Failed -> 3
                            is OperationDisplay.State.Cancelled -> 4
                            is OperationDisplay.State.Completed -> 5
                        }
                    }.thenByDescending { it.startedAt } // Newest first within each group
                )
            OperationsState(operations = ops)
        }
        .onStart { emit(OperationsState()) }
        .distinctUntilChanged()
        .asStateFlow()

    val state = combine(
        searchQuery,
        searchState,
        searcherSettings.maxHistoryItems.flow.flatMapLatest { searchHistory.getSearches(it) },
        currentFilter,
        searchTargets,
        searchTargets.flatMapLatest { targets ->
            val enabledPaths = targets.filterIsInstance<SearchTarget.Path>().filter { it.enabled }.map { it.path }
            if (enabledPaths.isEmpty()) {
                flowOf(PermissionState())
            } else {
                kotlinxCombine(enabledPaths.map { pathPermissionCheck.monitor(it) }) { states ->
                    // Combine all permission states - if any path needs permissions, show the card
                    PermissionState(
                        requirements = states.flatMap { it.requirements }.distinct(),
                        hasSufficientPermissions = states.all { it.hasSufficientPermissions },
                        missingCritical = states.flatMap { it.missingCritical }.distinct(),
                    )
                }
            }
        },
        selectionState,
        quickActionsResult,
        dialogStateFlow,
    ) { query, searchState, history, filter, targets, permissionState, selection, quickActions, dialogState ->
        State(
            id = id,
            searchQuery = query,
            searchState = searchState,
            searchHistory = history,
            currentFilter = filter,
            searchTargets = targets,
            caseSensitive = filter.caseSensitive,
            wholeWord = filter.wholeWord,
            useRegex = filter.useRegex,
            permissionState = permissionState,
            selectionState = selection.copy(selectableResults = searchState.results),
            quickActionsResult = quickActions,
            dialogState = dialogState,
        )
    }
        .distinctUntilChanged()
        .asStateFlow()

    fun updateSearchQuery(query: TextFieldValue) {
        log(TAG, INFO) { "Updating search query: ${query.text}" }
        searchQuery.value = query
    }

    fun performExplicitSearch() {
        log(TAG, INFO) { "Performing explicit search with history save" }

        // Check if the same search is already running
        val query = searchQuery.value.text
        if (query.isBlank()) return

        val targets = searchTargets.value
        val filter = currentFilter.value

        // Compare with currently running search parameters
        currentSearchParams?.let { runningParams ->
            val isSameSearch = runningParams.query == query &&
                    runningParams.targets == targets &&
                    runningParams.filter == filter

            if (isSameSearch && searchState.value.status == SearchState.Status.SEARCHING) {
                log(TAG, INFO) { "Same search already running, skipping duplicate" }
                return
            }
        }

        performSearch(saveToHistory = true)
    }

    fun restoreFromHistory(item: SearchHistory.SearchHistoryItem) {
        log(TAG, INFO) { "Restoring search from history: ${item.baseQuery}" }
        item.searchQuery?.let { query ->
            // Update all parameters atomically
            searchQuery.value = TextFieldValue(query.query)
            searchTargets.value = query.targets
            currentFilter.value = query.filter

            // Set initial progress immediately for instant UI feedback
            val initialProgress = (query.targets.firstOrNull() as? SearchTarget.Path)?.let { firstTarget ->
                SearchEngine.SearchProgress(
                    currentPath = firstTarget.path,
                    itemsScanned = 0,
                    resultsFound = 0
                )
            }

            // Set SEARCHING state immediately to prevent "no results" flash
            // LaunchedEffect in UI will trigger actual search after debounce delay (500ms)
            // This gives gateway resources time to initialize
            searchState.update {
                it.copy(
                    status = SearchState.Status.SEARCHING,
                    results = emptyList(),
                    progress = initialProgress,
                    error = null
                )
            }
        } ?: run {
            // Fallback for legacy history items without full query
            searchQuery.value = TextFieldValue(item.baseQuery)
            // LaunchedEffect will handle the search trigger
        }
    }

    fun performSearch(saveToHistory: Boolean = false) {
        val query = searchQuery.value.text
        if (query.isBlank()) return

        log(TAG, INFO) { "Performing search: $query" }

        activeSearchJob?.cancel()

        // Get targets early to provide immediate progress feedback
        val targets = searchTargets.value

        // Set initial progress with first target for immediate contextual feedback
        val initialProgress = (targets.firstOrNull() as? SearchTarget.Path)?.let { firstTarget ->
            SearchEngine.SearchProgress(
                currentPath = firstTarget.path,
                itemsScanned = 0,
                resultsFound = 0
            )
        }

        // Clear previous results and set searching state with initial progress
        searchState.update {
            it.copy(
                status = SearchState.Status.SEARCHING,
                results = emptyList(),
                progress = initialProgress,
                error = null
            )
        }

        // Start the search
        activeSearchJob = vmScope.launch {
            if (targets.isEmpty()) {
                log(TAG, WARN) { "Cannot perform search: no search targets configured" }
                searchState.update { it.copy(status = SearchState.Status.ERROR, error = Exception("No search targets configured")) }
                return@launch
            }

            val searchRequest = SearchQuery(
                query = query,
                targets = targets,
                options = SearchQuery.Options(
                    maxResults = searcherSettings.maxSearchResults.value()
                ),
                filter = currentFilter.value
            )

            // Store current search parameters for duplicate detection
            currentSearchParams = searchRequest

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
                    // Update selection state with new results
                    selectionState.update { selection ->
                        selection.copy(selectableResults = searchState.value.results + result)
                    }
                    log(TAG) { "Search result: ${result.path}" }
                }

                // Update history with result count
                currentSearchId?.let { id ->
                    searchHistory.updateResultCount(id, results.size)
                }

                searchState.update { it.copy(status = SearchState.Status.COMPLETED) }
                // Update final selection state
                selectionState.update { selection ->
                    selection.copy(selectableResults = results)
                }
                // Clear search params after successful completion
                currentSearchParams = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                searchState.update { it.copy(status = SearchState.Status.CANCELLED) }
                // Clear search params after cancellation
                currentSearchParams = null
                throw e
            } catch (e: Exception) {
                log(TAG) { "Search error: $e" }
                searchState.update { it.copy(status = SearchState.Status.ERROR, error = e) }
                // Clear search params after error
                currentSearchParams = null
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
        // Clear selection state
        selectionState.value = SearcherSelectionState()
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

    fun updateSearchTargets(targets: List<SearchTarget>) {
        log(TAG) { "Updating search targets: $targets" }
        searchTargets.value = targets
        vmScope.launch {
            searcherSettings.defaultSearchTargets.value(targets)
        }
    }

    fun addSearchTarget(path: APath<*>) {
        log(TAG) { "Adding search target: $path" }
        val target = SearchTarget.Path.from(path)
        val newTargets = (searchTargets.value + target).distinctBy { (it as? SearchTarget.Path)?.path }
        updateSearchTargets(newTargets)
    }

    fun removeSearchTarget(target: SearchTarget) {
        log(TAG) { "Removing search target: ${(target as? SearchTarget.Path)?.path}" }
        val newTargets = searchTargets.value.filter {
            (it as? SearchTarget.Path)?.path != (target as? SearchTarget.Path)?.path
        }
        updateSearchTargets(newTargets)
    }

    fun toggleTargetEnabled(target: SearchTarget) {
        log(TAG) { "Toggling enabled state for target: ${(target as? SearchTarget.Path)?.path}" }
        val newTargets = searchTargets.value.map {
            if ((it as? SearchTarget.Path)?.path == (target as? SearchTarget.Path)?.path) {
                when (it) {
                    is SearchTarget.Path -> it.copy(enabled = !it.enabled)
                }
            } else {
                it
            }
        }
        updateSearchTargets(newTargets)
    }

    fun updateTargetLabel(target: SearchTarget, label: String?) {
        log(TAG) { "Updating label for target: ${(target as? SearchTarget.Path)?.path} to: $label" }
        val newTargets = searchTargets.value.map {
            if ((it as? SearchTarget.Path)?.path == (target as? SearchTarget.Path)?.path) {
                when (it) {
                    is SearchTarget.Path -> it.copy(label = label)
                }
            } else {
                it
            }
        }
        updateSearchTargets(newTargets)
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
            if (currentQuery.isNotBlank() && searchTargets.value.isNotEmpty()) {
                val searchRequest = SearchQuery(
                    query = currentQuery,
                    targets = searchTargets.value,
                    options = SearchQuery.Options(
                        maxResults = searcherSettings.maxSearchResults.value()
                    ),
                    filter = currentFilter.value
                )
                searchHistory.addSearch(searchRequest)
            }
        }

        // Show quick actions for the clicked result
        quickActionsResult.value = result
    }

    // Selection and action methods
    fun showQuickActions(result: SearchResult) {
        log(TAG) { "Showing quick actions for: ${result.path}" }
        quickActionsResult.value = result
    }

    fun hideQuickActions() {
        quickActionsResult.value = null
    }

    fun enterSelectionMode(result: SearchResult) {
        log(TAG) { "Entering selection mode with: ${result.path}" }
        selectionState.update { it.enterSelectionMode(result) }
        hideQuickActions()
    }

    fun toggleSelection(result: SearchResult) {
        log(TAG) { "Toggling selection for: ${result.path}" }
        selectionState.update { it.toggleSelection(result) }
    }

    fun selectAll() {
        log(TAG) { "Selecting all results" }
        selectionState.update { it.selectAll() }
    }

    fun deselectAll() {
        log(TAG) { "Deselecting all results" }
        selectionState.update { it.deselectAll() }
    }

    fun onAction(action: SearcherAction) {
        log(TAG) { "Executing action: ${action.javaClass.simpleName}" }

        when (action) {
            is SearcherAction.Copy -> {
                vmScope.launch {
                    clipboardRepo.add(
                        ClipboardClip.Paths(
                            origin = id,
                            mode = ClipboardClip.Paths.Mode.COPY,
                            paths = action.results.map { it.path }
                        )
                    )
                }
            }
            is SearcherAction.Cut -> {
                vmScope.launch {
                    clipboardRepo.add(
                        ClipboardClip.Paths(
                            origin = id,
                            mode = ClipboardClip.Paths.Mode.CUT,
                            paths = action.results.map { it.path }
                        )
                    )
                }
            }
            is SearcherAction.Delete -> {
                vmScope.launch {
                    val paths = action.results.map { it.path }.toSet()
                    dialogEvents.emit(SearcherDialogEvent.ShowDeleteConfirmation(paths))
                }
            }
            is SearcherAction.Share -> {
                vmScope.launch {
                    try {
                        shareFiles(action.results)
                    } catch (e: Exception) {
                        log(TAG, ERROR) { "Failed to share files: ${e.asLog()}" }
                        errorEvents.tryEmit(e)
                    }
                }
                deselectAll()
            }
            is SearcherAction.OpenInEditor -> {
                launch {
                    workspaceRemote.createAndFocus(
                        type = Workspace.Type.EDITOR,
                        arguments = EditorArguments(
                            filePath = action.result.path
                        )
                    )
                }
            }
            is SearcherAction.OpenInExplorer -> {
                launch {
                    if (action.result.path is LocalPath) {
                        val parentPath = (action.result.path as LocalPath).parent
                        if (parentPath != null) {
                            workspaceRemote.createAndFocus(
                                type = Workspace.Type.EXPLORER,
                                arguments = ExternalExplorerArguments(
                                    startPath = parentPath
                                )
                            )
                        }
                    }
                }
            }
            is SearcherAction.CopyPath -> {
                log(TAG, INFO) { "Copying path to system clipboard: ${action.result.path.path}" }
                systemClipboardHelper.copyToClipboard(action.result.path.path)
            }
            is SearcherAction.Properties -> {
                showFileProperties(action.result)
            }
            is SearcherAction.SelectAll -> selectAll()
            is SearcherAction.DeselectAll -> deselectAll()
        }
        hideQuickActions()
    }

    private suspend fun onWorkspaceAction(action: WorkspaceAction) {
        log(TAG) { "Executing workspace action: ${action.javaClass.simpleName}" }
        workspaceRemote.execute(action)
    }

    private fun shareFiles(results: List<SearchResult>) {
        log(TAG, INFO) { "Sharing ${results.size} file(s)" }

        try {
            val intent = if (results.size == 1) {
                createShareIntent(results.first())
            } else {
                createShareMultipleIntent(results)
            }

            if (intent != null) {
                val chooser = Intent.createChooser(
                    intent,
                    if (results.size == 1) {
                        "Share ${results.first().name}"
                    } else {
                        "Share ${results.size} files"
                    }
                )
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(chooser)
                log(TAG, INFO) { "Share intent launched successfully" }
            } else {
                log(TAG, WARN) { "Failed to create share intent" }
                throw Exception("Failed to create share intent for selected files")
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Error creating share intent: ${e.asLog()}" }
            throw e
        }
    }

    private fun createShareIntent(result: SearchResult): Intent? {
        return try {
            val path = result.path
            if (path !is LocalPath) {
                log(TAG, WARN) { "Share only supported for local paths, got: ${path::class.simpleName}" }
                return null
            }

            val file = File(path.path)
            if (!file.exists()) {
                log(TAG, WARN) { "File does not exist: ${path.path}" }
                return null
            }

            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )

            val mimeType = getMimeType(file.name) ?: "*/*"

            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to create share intent for ${result.name}: ${e.asLog()}" }
            null
        }
    }

    private fun createShareMultipleIntent(results: List<SearchResult>): Intent? {
        return try {
            val uris = results.mapNotNull { result ->
                val path = result.path
                if (path !is LocalPath) {
                    log(TAG, WARN) { "Skipping non-local path: ${path::class.simpleName}" }
                    return@mapNotNull null
                }

                val file = File(path.path)
                if (!file.exists()) {
                    log(TAG, WARN) { "File does not exist: ${path.path}" }
                    return@mapNotNull null
                }

                try {
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to get URI for ${file.name}: ${e.asLog()}" }
                    null
                }
            }

            if (uris.isEmpty()) {
                log(TAG, WARN) { "No valid URIs created for sharing" }
                return null
            }

            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*" // Use generic type for multiple files
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to create share multiple intent: ${e.asLog()}" }
            null
        }
    }

    private fun getMimeType(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else {
            null
        }
    }

    data class State(
        val id: Workspace.Id,
        val searchQuery: TextFieldValue = TextFieldValue(""),
        val searchState: SearchState = SearchState(),
        val searchHistory: List<SearchHistory.SearchHistoryItem> = emptyList(),
        val currentFilter: SearchQuery.Filter = SearchQuery.Filter(),
        val searchTargets: List<SearchTarget>,
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
        val permissionState: PermissionState = PermissionState(),
        val selectionState: SearcherSelectionState = SearcherSelectionState(),
        val quickActionsResult: SearchResult? = null,
        val dialogState: SearcherDialogState = SearcherDialogState.None,
    ) {
        val isSearching: Boolean
            get() = searchState.status == SearchState.Status.SEARCHING

        val hasResults: Boolean
            get() = searchState.results.isNotEmpty()

        val needsPermissions: Boolean
            get() = permissionState.needsPermissions

        val listItems: List<SearchListItem>
            get() = buildList {
                // Add error item at the top if there's an error
                searchState.error?.let { error ->
                    add(
                        SearchListItem.Error(
                            throwable = error,
                            timestamp = kotlin.time.Clock.System.now()
                        )
                    )
                }

                // Add all search results
                searchState.results.forEach { result ->
                    add(
                        SearchListItem.Result(
                            fileRowData = FileRowData(
                                lookup = result.lookup,
                                metadata = emptyMap(),
                                matchContext = result.matchContext?.let { context ->
                                    FileRowData.MatchContext(
                                        lineNumber = context.lineNumber,
                                        matchedLine = context.matchedLine
                                    )
                                }
                            )
                        )
                    )
                }
            }
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

    private suspend fun handleDialogEvent(event: SearcherDialogEvent) {
        log(TAG) { "handleDialogEvent($event)" }
        when (event) {
            is SearcherDialogEvent.ShowDeleteConfirmation -> {
                dialogStateFlow.value = SearcherDialogState.DeleteConfirmation(event.paths)
            }
            is SearcherDialogEvent.Dismiss -> {
                dialogStateFlow.value = SearcherDialogState.None
            }
        }
    }

    fun dismissDialog() {
        dialogStateFlow.value = SearcherDialogState.None
    }

    fun onDeleteConfirmed(items: Set<APath<*>>,) = launch {
        log(TAG, INFO) { "onDeleteConfirmed(${items.size} items)" }
        dialogStateFlow.value = SearcherDialogState.None

        if (items.isNotEmpty()) {
            getWorkspace().execute(
                SearcherCommand.Delete(
                    targets = items,
                )
            )
            deselectAll()
        }
    }

    fun removeClipboardEntry(clip: ClipboardClip) = launch {
        log(TAG) { "removeClipboardEntry($clip)" }
        clipboardRepo.remove(clip.id)
        dismissDialog()
    }

    fun clearAllClipboard() = launch {
        log(TAG) { "clearAllClipboard()" }
        clipboardRepo.clear()
    }

    fun showFileProperties(result: SearchResult) {
        log(TAG) { "showFileProperties(${result.name})" }
        dialogStateFlow.value = SearcherDialogState.FileInfo(result)
    }

    fun showClipboardInfo(clip: ClipboardClip) {
        log(TAG) { "showClipboardInfo($clip)" }
        dialogStateFlow.value = SearcherDialogState.ClipboardInfo(clip)
    }

    fun navigateToClipboardSource(clip: ClipboardClip) = launch {
        log(TAG) { "navigateToClipboardSource($clip)" }
        dismissDialog()

        when (clip) {
            is ClipboardClip.Paths -> {
                if (clip.paths.isNotEmpty()) {
                    val firstPath = clip.paths.first()
                    val parentPath = firstPath.parent
                    if (parentPath != null) {
                        // Open Explorer at the source path and switch to it
                        workspaceRemote.createAndFocus(
                            type = Workspace.Type.EXPLORER,
                            arguments = ExternalExplorerArguments(startPath = parentPath)
                        )
                    }
                }
            }
        }
    }

    fun openClipboardInExplorer(clip: ClipboardClip) = launch {
        log(TAG) { "openClipboardInExplorer($clip)" }

        when (clip) {
            is ClipboardClip.Paths -> {
                if (clip.paths.isEmpty()) {
                    log(TAG, WARN) { "Cannot open in Explorer - clipboard has no paths" }
                    return@launch
                }

                val commonParent = clip.paths.commonParent()
                if (commonParent == null) {
                    log(TAG, WARN) { "Cannot open in Explorer - paths have no common parent" }
                    return@launch
                }

                log(TAG) { "Opening Explorer at common parent: $commonParent" }
                workspaceRemote.createAndFocus(
                    type = Workspace.Type.EXPLORER,
                    arguments = ExternalExplorerArguments(startPath = commonParent)
                )
            }
        }
    }

    fun copyPathToSystemClipboard(text: String) {
        log(TAG) { "copyPathToSystemClipboard($text)" }
        systemClipboardHelper.copyToClipboard(text)
    }

    fun cancelOperation(id: Operation.Id) = launch {
        log(TAG) { "cancelOperation($id)" }
        operationsManager.cancel(id)
    }

    fun dismissOperation(id: Operation.Id) = launch {
        log(TAG) { "dismissOperation($id)" }
        operationsManager.remove(id)
    }

    fun clearCompletedOperations() = launch {
        log(TAG) { "clearCompletedOperations()" }
        operationsManager.clearCompleted()
    }

    fun copyError(id: Operation.Id) = launch {
        log(TAG) { "copyError($id)" }
        val operation = operationsManager.get(id)
        if (operation == null) {
            log(TAG, ERROR) { "Operation with id $id not found" }
            return@launch
        }
        val state = operation.state.value as? Operation.State.Completed
        if (state == null || state.error == null) {
            log(TAG, ERROR) { "Operation is not complete or has no error: $operation" }
            return@launch
        }
        val errorText = """
            # Operation error
            * OperationID: `${operation.id}`
            * Source: ${operation.metadata.origin}
            * CompletedAt: ${state.completedAt}

            ## Description
            **${operation.metadata.title.get(appContext)}**

            ${operation.metadata.description.get(appContext)}

            ## Error
            ${state.summary.get(appContext)}

            ```java
            ${state.error?.asLog()}
            ```

            ## Command
            ```
            ${operation.operation}
            ```
        """.trimIndent()
        systemClipboardHelper.copyToClipboard(errorText)
    }

    fun copySearchError(throwable: Throwable) {
        log(TAG) { "copySearchError(${throwable.javaClass.simpleName})" }
        val errorText = eu.darken.butler.workspace.ui.error.ErrorFormatter.formatErrorForClipboard(
            throwable = throwable,
            context = "Search operation in workspace ${id.shortTag}"
        )
        systemClipboardHelper.copyToClipboard(errorText)
    }

    fun showConflictSheet(operationId: Operation.Id) = launch {
        log(TAG) { "showConflictSheet($operationId): Requesting to show conflict sheet" }

        // Get current conflicts map
        val workspace = getWorkspace()
        val operationsState = workspace.operations.first()
        val conflicts = operationsState.pendingConflicts
        val issue = conflicts[operationId]

        if (issue != null) {
            // TODO: Show conflict sheet for searcher
            log(TAG, WARN) { "Conflict sheet not yet implemented for searcher: $issue" }
        } else {
            log(TAG, WARN) { "Cannot show conflict sheet: no conflict for operation $operationId" }
        }
    }

    fun openPathPicker() = launch {
        workspaceRemote.launchPicker(id, startPath = null, PickerConfig.Selection.DirectoryMulti)
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SearcherWorkspaceViewModel
    }

    companion object {
        private val TAG = logTag("Searcher", "Workspace", "ViewModel")
    }
}
