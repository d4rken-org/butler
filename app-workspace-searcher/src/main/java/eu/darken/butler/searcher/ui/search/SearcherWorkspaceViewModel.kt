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
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.searcher.core.SearchEngine
import eu.darken.butler.searcher.core.SearchHistory
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogEvent
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogState
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.get
import eu.darken.butler.workspace.core.permissions.PathPermissionCheck
import eu.darken.butler.workspace.core.permissions.PermissionState
import eu.darken.butler.workspace.core.permissions.check
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
    private val searchPath = MutableStateFlow<APath<*>>(LocalPath.build(Environment.getExternalStorageDirectory()))
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
            val defaultPath = searcherSettings.defaultSearchPath.value()
            searchPath.value = defaultPath ?: LocalPath.build(Environment.getExternalStorageDirectory())
        }

        // Handle dialog events
        dialogEvents
            .onEach { event -> handleDialogEvent(event) }
            .launchIn(vmScope)
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
        .asStateFlow()

    val state = combine(
        searchQuery,
        searchState,
        searcherSettings.maxHistoryItems.flow.flatMapLatest { searchHistory.getSearches(it) },
        currentFilter,
        searchPath,
        selectionState,
        quickActionsResult,
        dialogStateFlow,
    ) { values ->
        val query = values[0] as TextFieldValue
        val searchState = values[1] as SearchState
        val history = values[2] as List<SearchHistory.SearchHistoryItem>
        val filter = values[3] as SearchQuery.Filter
        val path = values[4] as APath<*>
        val selection = values[5] as SearcherSelectionState
        val quickActions = values[6] as SearchResult?
        val dialogState = values[7] as SearcherDialogState

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
            permissionState = pathPermissionCheck.check(path),
            selectionState = selection.copy(selectableResults = searchState.results),
            quickActionsResult = quickActions,
            dialogState = dialogState,
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

    fun updateSearchPath(path: APath<*>) {
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
                    onWorkspaceAction(
                        WorkspaceAction.Create(
                            type = Workspace.Type.EDITOR,
                            arguments = EditorArguments(
                                filePath = action.result.path
                            )
                        )
                    )
                }
            }
            is SearcherAction.OpenInExplorer -> {
                launch {
                    if (action.result.path is LocalPath) {
                        val parentPath = (action.result.path as LocalPath).parent
                        if (parentPath != null) {
                            onWorkspaceAction(
                                WorkspaceAction.Create(
                                    type = Workspace.Type.EXPLORER,
                                    arguments = ExplorerArguments(
                                        startPath = parentPath
                                    )
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
                // TODO: Implement file properties dialog
                log(TAG, WARN) { "Properties action not yet implemented" }
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
        val searchPath: APath<*>,
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
    }

    fun clearAllClipboard() = launch {
        log(TAG) { "clearAllClipboard()" }
        clipboardRepo.clear()
    }

    fun showClipboardInfo(clip: ClipboardClip) {
        log(TAG) { "showClipboardInfo($clip)" }
        // TODO: Implement clipboard info dialog for searcher
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

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SearcherWorkspaceViewModel
    }

    companion object {
        private val TAG = logTag("Searcher", "Workspace", "ViewModel")
    }
}
