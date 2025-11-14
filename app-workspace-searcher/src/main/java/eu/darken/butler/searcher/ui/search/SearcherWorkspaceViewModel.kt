package eu.darken.butler.searcher.ui.search

import android.content.Context
import android.content.Intent
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
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.extensions.commonParent
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.arguments.ExternalExplorerArguments
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogEvent
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogState
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.ShareIntentUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.get
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
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
    private val searchHistory: SearchHistory,
    private val searcherSettings: SearcherSettings,
    private val clipboardRepo: ClipboardRepo,
    private val operationsManager: OperationsManager,
    private val workspaceRemote: WorkspaceRemote,
    private val workspaceProvider: WorkspaceProvider,
    private val systemClipboardHelper: SystemClipboardHelper,
    private val openInNewTabsUseCase: OpenInNewTabsUseCase,
    private val shareIntentUseCase: ShareIntentUseCase,
    itemSorterFactory: eu.darken.butler.searcher.core.sorting.SearchItemSorter.Factory,
) : ViewModel4(dispatchers, logTag("Searcher", "Workspace", id.shortTag, "Page"), navCtrl) {

    private val itemSorter = itemSorterFactory.create(id)

    private val workspaceSource: Flow<SearcherWorkspace?> =
        workspaceProvider.retrieve(id).map { workspace: Workspace? -> workspace as? SearcherWorkspace }

    private suspend fun getWorkspace(): SearcherWorkspace = workspaceSource.filterNotNull().first()

    private val searchQuery = MutableStateFlow(TextFieldValue(""))
    private val currentFilter = MutableStateFlow(SearchQuery.Filter())
    private val selectionState = MutableStateFlow(SearcherSelectionState())
    private val quickActionsResult = MutableStateFlow<SearchItem?>(null)
    private val dialogStateFlow = MutableStateFlow<SearcherDialogState>(SearcherDialogState.None)
    private val currentSortSettings = MutableStateFlow<eu.darken.butler.searcher.core.SearchSortSettings>(searcherSettings.sortSettings.valueBlocking)
    private val viewModeFlow = MutableStateFlow<ViewMode>(ViewMode.LIST)
    private var lastAutoExecutedQuery: String? = null
    private var currentSearchId: String? = null

    enum class ViewMode {
        LIST,
        GRID
    }

    val dialogEvents = SingleEventFlow<SearcherDialogEvent>()

    // Observe workspace search state
    private val workspaceSearchState: Flow<SearcherWorkspace.State> = workspaceSource
        .filterNotNull()
        .flatMapLatest { it.state }

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

        // Handle dialog events
        dialogEvents
            .onEach { event -> handleDialogEvent(event) }
            .launchIn(vmScope)

        // Listen for picker results
        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag, INFO) { "Received picker result: ${result.selectedPaths}" }
                if (result.selectedPaths.isNotEmpty()) {
                    vmScope.launch {
                        val workspace = getWorkspace()
                        // Add each selected path, deduplicating
                        result.selectedPaths.forEach { path ->
                            workspace.updateTargets { current ->
                                (current + SearchTarget.Path.from(path)).distinctBy { (it as? SearchTarget.Path)?.path }
                            }
                        }
                    }
                }
            }
            .launchIn(vmScope)

        // Auto-search on query text changes with debouncing
        searchQuery
            .debounce(500)
            .map { it.text }
            .distinctUntilChanged()
            .filter { it.isNotBlank() }
            .filter { it != lastAutoExecutedQuery }
            .onEach { query ->
                log(tag, INFO) { "Auto-triggering search for query: $query" }
                lastAutoExecutedQuery = query
                performSearch(saveToHistory = true)
            }
            .launchIn(vmScope)

        // Auto-search on target changes (when query exists)
        workspaceSearchState
            .map { it.searchTargets }
            .distinctUntilChanged()
            .drop(1) // Skip initial state to avoid triggering on setup
            .debounce(300) // Short debounce for rapid changes
            .filter { searchQuery.value.text.isNotBlank() }
            .onEach { targets ->
                log(tag, INFO) { "Auto-triggering search due to target change: ${targets.size} targets" }
                performSearch(saveToHistory = true)
            }
            .launchIn(vmScope)

        // Update history with result counts when search completes
        workspaceSearchState
            .onEach { wsState ->
                if (wsState.searchStatus == SearcherWorkspace.State.SearchStatus.COMPLETED) {
                    currentSearchId?.let { id ->
                        searchHistory.updateResultCount(id, wsState.results.size)
                        currentSearchId = null
                    }
                }

                // Update selection state when results change
                selectionState.update { selection ->
                    selection.copy(selectableResults = wsState.results)
                }
            }
            .launchIn(vmScope)
    }

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

    val state: Flow<State> = combine(
        searchQuery,
        workspaceSearchState,
        searcherSettings.maxHistoryItems.flow.flatMapLatest { searchHistory.getSearches(it) },
        currentFilter,
        selectionState,
        quickActionsResult,
        dialogStateFlow,
        currentSortSettings,
        viewModeFlow,
    ) { query: TextFieldValue, workspaceState: SearcherWorkspace.State, history: List<SearchHistory.SearchHistoryItem>, filter: SearchQuery.Filter, selection: SearcherSelectionState, quickActions: SearchItem?, dialogState: SearcherDialogState, sortSettings: eu.darken.butler.searcher.core.SearchSortSettings, viewMode: ViewMode ->
        val sortedResults = itemSorter.sortItems(workspaceState.results, sortSettings)
        val updatedWorkspaceState = workspaceState.copy(results = sortedResults)
        val updatedSelectionState = selection.copy(selectableResults = sortedResults)

        // Calculate available actions based on selection state
        val actions = if (updatedSelectionState.selectedResultIds.isNotEmpty()) {
            buildList {
                // Select All / Deselect All
                if (updatedSelectionState.isAllSelected) {
                    add(SearcherAction.DeselectAll)
                } else if (updatedSelectionState.selectableResults.isNotEmpty()) {
                    add(SearcherAction.SelectAll)
                }

                // Open in New Tabs
                add(SearcherAction.OpenInNewTabs(updatedSelectionState.selectedResults))

                // Copy
                add(SearcherAction.Copy(updatedSelectionState.selectedResults))

                // Cut
                add(SearcherAction.Cut(updatedSelectionState.selectedResults))

                // Share (if reasonable number of items)
                val shareAction = SearcherAction.Share(updatedSelectionState.selectedResults)
                if (shareAction.isVisible) {
                    add(shareAction)
                }

                // Delete
                add(SearcherAction.Delete(updatedSelectionState.selectedResults))
            }
        } else if (sortedResults.isNotEmpty()) {
            buildList {
                add(SearcherAction.Common.Sort())
                add(SearcherAction.Common.ToggleView())
            }
        } else {
            emptyList()
        }

        State(
            id = id,
            searchQuery = query,
            workspaceState = updatedWorkspaceState,
            searchHistory = history,
            currentFilter = filter,
            searchTargets = workspaceState.searchTargets,
            caseSensitive = filter.caseSensitive,
            wholeWord = filter.wholeWord,
            useRegex = filter.useRegex,
            setupRequirements = workspaceState.setupRequirements,
            selectionState = updatedSelectionState,
            quickActionsResult = quickActions,
            dialogState = dialogState,
            availableActions = actions,
            viewMode = viewMode,
            sortSettings = sortSettings,
        )
    }
        .distinctUntilChanged()
        .asStateFlow()

    fun restoreFromHistory(item: SearchHistory.SearchHistoryItem) {
        log(TAG, INFO) { "Restoring search from history: ${item.baseQuery}" }
        item.searchQuery?.let { query ->
            // Update all parameters atomically
            searchQuery.value = TextFieldValue(query.query)
            currentFilter.value = query.filter

            // Update targets
            vmScope.launch {
                val workspace = getWorkspace()
                workspace.updateTargets { query.targets }
            }

            // Clear selection state when restoring from history
            selectionState.value = SearcherSelectionState()

            // Prevent auto-search from double-triggering
            lastAutoExecutedQuery = query.query

            // Explicitly trigger search (don't rely on auto-search)
            performSearch(saveToHistory = false)
        } ?: run {
            // Fallback for legacy history items without full query
            searchQuery.value = TextFieldValue(item.baseQuery)
            lastAutoExecutedQuery = item.baseQuery
            performSearch(saveToHistory = false)
        }
    }

    fun performSearch(saveToHistory: Boolean = false) {
        val query = searchQuery.value.text
        if (query.isBlank()) return

        log(TAG, INFO) { "Performing search: $query" }

        // Clear selection state when starting new search
        selectionState.value = SearcherSelectionState()

        // Execute search via workspace
        vmScope.launch {
            val workspace = getWorkspace()
            val targets = workspace.state.first().searchTargets

            if (targets.isEmpty()) {
                log(TAG, WARN) { "Cannot perform search: no search targets configured" }
                return@launch
            }

            // Build search command (inside coroutine to access suspend .value())
            val searchCommand = SearcherCommand.Search(
                query = query,
                targets = targets,
                filter = currentFilter.value,
                options = SearchQuery.Options(
                    maxResults = searcherSettings.maxSearchResults.value()
                ),
                saveToHistory = saveToHistory,
            )

            workspace.execute(searchCommand)

            // Record search in history only if explicitly requested
            if (saveToHistory) {
                val searchRequest = SearchQuery(
                    query = query,
                    targets = targets,
                    options = searchCommand.options,
                    filter = searchCommand.filter
                )
                currentSearchId = searchHistory.addSearch(searchRequest)
            }
        }
    }

    fun cancelSearch() {
        log(TAG) { "Cancelling search" }
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.execute(SearcherCommand.Cancel)
        }
    }

    fun clearResults() {
        log(TAG) { "Clearing search results" }
        searchQuery.value = TextFieldValue("")
        // Clear selection state
        selectionState.value = SearcherSelectionState()
        // Clear workspace state via command
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.execute(SearcherCommand.Clear)
        }
    }

    fun updateFilter(filter: SearchQuery.Filter) {
        log(TAG) { "Updating filter: $filter" }
        currentFilter.value = filter
        // Clear selection state when filter changes
        selectionState.value = SearcherSelectionState()
    }


    fun updateSearchTargets(targets: List<SearchTarget>) {
        log(TAG) { "Updating search targets: ${targets.size} targets" }
        // Clear selection state when targets change
        selectionState.value = SearcherSelectionState()
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.updateTargets { targets }
        }
    }

    fun addSearchTarget(path: APath<*>) {
        log(TAG) { "Adding search target: $path" }
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.updateTargets { current ->
                (current + SearchTarget.Path.from(path)).distinctBy { (it as? SearchTarget.Path)?.path }
            }
        }
    }

    fun removeSearchTarget(target: SearchTarget) {
        log(TAG) { "Removing search target: ${(target as? SearchTarget.Path)?.path}" }
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.updateTargets { current ->
                current.filter { (it as? SearchTarget.Path)?.path != (target as? SearchTarget.Path)?.path }
            }
        }
    }

    fun toggleTargetEnabled(target: SearchTarget) {
        log(TAG) { "Toggling target enabled: ${(target as? SearchTarget.Path)?.path}" }
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.updateTargets { current ->
                current.map {
                    if ((it as? SearchTarget.Path)?.path == (target as? SearchTarget.Path)?.path) {
                        when (it) {
                            is SearchTarget.Path -> it.copy(enabled = !it.enabled)
                        }
                    } else it
                }
            }
        }
    }

    // Selection and action methods
    fun hideQuickActions() {
        quickActionsResult.value = null
    }

    fun enterSelectionMode(result: SearchItem) {
        log(TAG) { "Entering selection mode with: ${result.path}" }
        selectionState.update { it.enterSelectionMode(result) }
        hideQuickActions()
    }

    fun toggleSelection(result: SearchItem) {
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
            is SearcherAction.Common.Sort -> {
                dialogStateFlow.value = SearcherDialogState.EditSortOptions(
                    currentSortSettings = currentSortSettings.value
                )
            }
            is SearcherAction.Common.ToggleView -> {
                viewModeFlow.value = when (viewModeFlow.value) {
                    ViewMode.LIST -> ViewMode.GRID
                    ViewMode.GRID -> ViewMode.LIST
                }
            }
            is SearcherAction.OpenInNewTabs -> {
                vmScope.launch {
                    log(TAG) { "openInNewTabs(): ${action.results.size} items" }

                    // Convert SearchItems to use case items
                    val items = action.results.map { item ->
                        if (item.fileType == FileType.DIRECTORY) {
                            OpenInNewTabsUseCase.Item.Directory(item.path)
                        } else {
                            val isText = TextFileDetector.isTextFile(item.path)
                            OpenInNewTabsUseCase.Item.File(item.path, isText)
                        }
                    }

                    val request = OpenInNewTabsUseCase.Request(
                        items = items,
                        sourceWorkspaceId = id,
                    )

                    val analysis = openInNewTabsUseCase.analyze(request)

                    if (!analysis.hasItemsToOpen) {
                        log(TAG, WARN) { "All items skipped (no openable items)" }
                        return@launch
                    }

                    // Always emit event - WorkspacesViewModel handles confirmation
                    executeOpenInNewTabs(analysis)
                }
            }
        }
        hideQuickActions()
    }

    private suspend fun executeOpenInNewTabs(analysis: OpenInNewTabsUseCase.AnalysisResult) {
        log(TAG, INFO) { "executeOpenInNewTabs(): Opening ${analysis.totalOpenableCount} workspaces" }

        // Create workspace requests
        val requests = openInNewTabsUseCase.createRequests(
            analysis = analysis,
            createExplorerArguments = { path -> ExternalExplorerArguments(startPath = path) },
            createEditorArguments = { path -> EditorArguments(filePath = path) },
        )

        // Execute batch creation directly - WorkspaceRepo handles confirmation and banner
        val result = workspaceRemote.execute(
            WorkspaceAction.CreateBatch(
                requests = requests,
                sourceWorkspaceId = id,
            )
        )

        when (result) {
            is WorkspaceAction.CreateBatch.Result.Success -> {
                log(TAG, INFO) { "Batch creation succeeded: $result" }
            }
            is WorkspaceAction.CreateBatch.Result.Cancelled -> {
                log(TAG, INFO) { "Batch creation cancelled by user" }
            }
        }

        deselectAll()
    }

    private fun shareFiles(results: List<SearchItem>) {
        log(TAG, INFO) { "Sharing ${results.size} file(s)" }

        try {
            val shareItems = results.map { result ->
                object : ShareIntentUseCase.Item {
                    override val path = result.path
                    override val mimeType = getMimeType(result.name)
                    override val displayName = result.name
                }
            }

            val intent = shareIntentUseCase.createShareIntent(shareItems)

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
        val workspaceState: SearcherWorkspace.State = SearcherWorkspace.State(),
        val searchHistory: List<SearchHistory.SearchHistoryItem> = emptyList(),
        val currentFilter: SearchQuery.Filter = SearchQuery.Filter(),
        val searchTargets: List<SearchTarget>,
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
        val setupRequirements: PathRequirements = PathRequirements(),
        val selectionState: SearcherSelectionState = SearcherSelectionState(),
        val quickActionsResult: SearchItem? = null,
        val dialogState: SearcherDialogState = SearcherDialogState.None,
        val availableActions: List<SearcherAction> = emptyList(),
        val viewMode: ViewMode = ViewMode.LIST,
        val sortSettings: eu.darken.butler.searcher.core.SearchSortSettings = eu.darken.butler.searcher.core.SearchSortSettings(),
    ) {
        val isSearching: Boolean
            get() = workspaceState.searchStatus == SearcherWorkspace.State.SearchStatus.SEARCHING

        val hasResults: Boolean
            get() = workspaceState.results.isNotEmpty()

        val needsSetup: Boolean
            get() = setupRequirements.needsSetup

        val listItems: List<SearchListItem>
            get() = buildList {
                // Add error item at the top if there's an error
                workspaceState.error?.let { error ->
                    add(
                        SearchListItem.Error(
                            throwable = error,
                            timestamp = kotlin.time.Clock.System.now()
                        )
                    )
                }

                // Add all search results
                workspaceState.results.forEach { result ->
                    add(
                        SearchListItem.Result(
                            searchItem = result
                        )
                    )
                }
            }
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

    fun onDeleteConfirmed(items: Set<APath<*>>) = launch {
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

    fun onSortOptions(result: eu.darken.butler.searcher.ui.search.dialogs.SearchSortOptionsResult) = launch {
        log(tag) { "onSortOptions($result)" }
        dialogStateFlow.value = SearcherDialogState.None
        searcherSettings.sortSettings.value(result.sortSettings)
        currentSortSettings.value = result.sortSettings
    }

    private fun showFileProperties(result: SearchItem) {
        log(TAG) { "showFileProperties(${result.name})" }
        dialogStateFlow.value = SearcherDialogState.FileInfo(result)
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

    fun removeClipboardEntry(clip: ClipboardClip) = launch {
        log(TAG) { "removeClipboardEntry($clip)" }
        clipboardRepo.remove(clip.id)
        dismissDialog()
    }

    fun cancelOperation(id: Operation.Id) = launch {
        log(TAG) { "cancelOperation($id)" }
        operationsManager.cancel(id)
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

    /**
     * Unified handler for all page-level actions.
     * Dispatches to appropriate ViewModel methods based on action type.
     */
    fun onPageAction(action: SearcherPageAction) {
        log(TAG, INFO) { "onPageAction(): $action" }
        when (action) {
            // Search actions
            is SearcherPageAction.Search.UpdateQuery -> {
                log(TAG, INFO) { "Updating search query: ${action.query.text}" }
                searchQuery.value = action.query
                // Auto-clear results when query becomes empty
                if (action.query.text.isBlank()) {
                    clearResults()
                }
            }
            is SearcherPageAction.Search.Perform -> performSearch()
            is SearcherPageAction.Search.Explicit -> {
                log(TAG, INFO) { "Performing explicit search with history save" }
                performSearch(saveToHistory = true)
            }
            is SearcherPageAction.Search.Cancel -> cancelSearch()
            is SearcherPageAction.Search.ClearResults -> clearResults()

            // Options
            is SearcherPageAction.Options.ToggleCaseSensitive -> {
                vmScope.launch {
                    val current = searcherSettings.caseSensitive.flow.first()
                    searcherSettings.caseSensitive.update { !current }
                }
            }
            is SearcherPageAction.Options.ToggleWholeWord -> {
                vmScope.launch {
                    val current = searcherSettings.wholeWord.flow.first()
                    searcherSettings.wholeWord.update { !current }
                }
            }
            is SearcherPageAction.Options.ToggleRegex -> {
                vmScope.launch {
                    val current = searcherSettings.useRegex.flow.first()
                    searcherSettings.useRegex.update { !current }
                }
            }

            // Targets
            is SearcherPageAction.Targets.Remove -> removeSearchTarget(action.target)
            is SearcherPageAction.Targets.ToggleEnabled -> toggleTargetEnabled(action.target)
            is SearcherPageAction.Targets.OpenPicker -> {
                launch {
                    workspaceRemote.launchPicker(id, startPath = null, PickerConfig.Selection.DirectoryMulti)
                }
            }
            is SearcherPageAction.Targets.AddDefaultPaths -> {
                vmScope.launch {
                    val workspace = getWorkspace()
                    workspace.execute(SearcherCommand.AddDefaultPaths)
                }
            }

            // History
            is SearcherPageAction.History.Clear -> {
                vmScope.launch {
                    searchHistory.clearHistory()
                }
            }
            is SearcherPageAction.History.Remove -> {
                vmScope.launch {
                    searchHistory.removeItem(action.item.id)
                }
            }
            is SearcherPageAction.History.Click -> restoreFromHistory(action.item)

            // Results
            is SearcherPageAction.Results.Click -> {
                log(TAG) { "Showing quick actions for: ${action.item.path}" }
                quickActionsResult.value = action.item
            }
            is SearcherPageAction.Results.EnterSelectionMode -> enterSelectionMode(action.item)
            is SearcherPageAction.Results.ToggleSelection -> toggleSelection(action.item)
            is SearcherPageAction.Results.ExitSelectionMode -> deselectAll()
            is SearcherPageAction.Results.HideQuickActions -> hideQuickActions()

            // Clipboard
            is SearcherPageAction.Clipboard.ClickEntry -> {
                log(TAG) { "showClipboardInfo(${action.clip})" }
                dialogStateFlow.value = SearcherDialogState.ClipboardInfo(action.clip)
            }
            is SearcherPageAction.Clipboard.RemoveEntry -> launch {
                log(TAG) { "removeClipboardEntry(${action.clip})" }
                clipboardRepo.remove(action.clip.id)
                dismissDialog()
            }
            is SearcherPageAction.Clipboard.ClearAll -> launch {
                log(TAG) { "clearAllClipboard()" }
                clipboardRepo.clear()
            }

            // Operations
            is SearcherPageAction.Operations.Cancel -> launch {
                log(TAG) { "cancelOperation(${action.id})" }
                operationsManager.cancel(action.id)
            }
            is SearcherPageAction.Operations.Dismiss -> launch {
                log(TAG) { "dismissOperation(${action.id})" }
                operationsManager.remove(action.id)
            }
            is SearcherPageAction.Operations.ClearCompleted -> launch {
                log(TAG) { "clearCompletedOperations()" }
                operationsManager.clearCompleted()
            }

            // Setup
            is SearcherPageAction.Setup.Open -> navTo(
                Nav.Main.destSetup(
                    typeFilter = action.requirements.relevantTypes,
                    satisfyingCombos = action.requirements.combos,
                    showCompleted = false,
                    autoCloseWhenComplete = true,
                )
            )

            // Error
            is SearcherPageAction.Error.Copy -> {
                log(TAG) { "copySearchError(${action.error.javaClass.simpleName})" }
                val errorText = eu.darken.butler.workspace.ui.error.ErrorFormatter.formatErrorForClipboard(
                    throwable = action.error,
                    context = "Search operation in workspace ${id.shortTag}"
                )
                systemClipboardHelper.copyToClipboard(errorText)
            }

            // Workspace actions (delegate to existing handler)
            is SearcherPageAction.WorkspaceAction -> onAction(action.action)
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
