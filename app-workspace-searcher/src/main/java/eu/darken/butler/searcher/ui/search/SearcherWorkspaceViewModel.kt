package eu.darken.butler.searcher.ui.search

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Stable
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
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.extensions.commonParent
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.arguments.EditorArguments
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.ContentQuery
import eu.darken.butler.searcher.core.FilenameQuery
import eu.darken.butler.searcher.core.FilterCondition
import eu.darken.butler.searcher.core.SearchFilter
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.core.SearchTemplate
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.arguments.SearcherArguments
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.searcher.core.sorting.SearchItemSorter
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogEvent
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogState
import eu.darken.butler.searcher.ui.search.util.SearchListItem
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.searcher.ui.search.util.SearcherSelectionState
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
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.toOperationsDisplayState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = SearcherWorkspaceViewModel.Factory::class)
class SearcherWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val appContext: Context,
    dispatchers: DispatcherProvider,
    private val searchHistory: SearchHistory,
    private val searcherSettings: SearcherSettings,
    private val clipboardRepo: ClipboardRepo,
    private val operationsManager: OperationsManager,
    private val workspaceRemote: WorkspaceRemote,
    private val workspaceProvider: WorkspaceProvider,
    private val systemClipboardHelper: SystemClipboardHelper,
    private val openInNewTabsUseCase: OpenInNewTabsUseCase,
    private val shareIntentUseCase: ShareIntentUseCase,
    private val trashSettings: TrashSettings,
    private val errorReportTool: ErrorReportTool,
    itemSorterFactory: SearchItemSorter.Factory,
) : ViewModel4(dispatchers, logTag("Searcher", "Workspace", id.shortTag, "Page")) {

    private val itemSorter = itemSorterFactory.create(id)

    private val workspaceSource: Flow<SearcherWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? SearcherWorkspace }

    private suspend fun getWorkspace(): SearcherWorkspace = workspaceSource.filterNotNull().first()

    private val filenameQuery = MutableStateFlow("")
    private val contentQuery = MutableStateFlow("")

    // Per-field options (workspace-local, loaded from defaults on init)
    private val filenameOptions = MutableStateFlow(FilenameQuery())
    private val contentOptions = MutableStateFlow(ContentQuery())

    private val contentSearchEnabled = MutableStateFlow(false)
    private val currentFilter = MutableStateFlow(SearchFilter())
    private val selectionState = MutableStateFlow(SearcherSelectionState())
    private val quickActionsResult = MutableStateFlow<SearchItem?>(null)
    private val dialogStateFlow = MutableStateFlow<SearcherDialogState>(SearcherDialogState.None)
    private val currentSortSettings = MutableStateFlow(searcherSettings.defaultSort.valueBlocking)
    private val viewStyleFlow = MutableStateFlow(searcherSettings.defaultViewStyle.valueBlocking)
    private var lastAutoExecutedQuery: String? = null
    private var currentSearchId: String? = null

    // Issue/conflict handling
    private val issueStateFlow = MutableStateFlow<eu.darken.butler.common.issue.Issue?>(null)
    val issueState = issueStateFlow
    private var currentIssueOperationId: Operation.Id? = null

    val dialogEvents = SingleEventFlow<SearcherDialogEvent>()

    val shareIntentEvent = SingleEventFlow<Intent>()

    // Observe workspace search state
    private val workspaceSearchState: Flow<SearcherWorkspace.State> = workspaceSource
        .filterNotNull()
        .flatMapLatest { it.state }

    init {
        // Initialize UI state from workspace (source of truth, already has defaults applied)
        // Non-blocking reactive initialization - waits for workspace to be ready
        workspaceSearchState
            .map { it.currentSearchQuery }
            .filterNotNull()
            .take(1)
            .onEach { query ->
                log(tag, INFO) { "Workspace ready, initializing UI state from query" }

                // Initialize UI state from workspace
                filenameOptions.value = query.filenameQuery.copy(pattern = "")
                contentOptions.value = query.contentQuery.copy(pattern = "")
                contentSearchEnabled.value = query.contentQuery.isNotEmpty

                query.filenameQuery.pattern.takeIf { it.isNotBlank() }?.let {
                    filenameQuery.value = it
                }
                query.contentQuery.pattern.takeIf { it.isNotBlank() }?.let {
                    contentQuery.value = it
                }

                currentFilter.value = query.filter

                // Prevent auto-search flow from triggering on restored queries
                if (filenameQuery.value.isNotBlank() || contentQuery.value.isNotBlank()) {
                    lastAutoExecutedQuery = "${filenameQuery.value}|${contentQuery.value}"
                }

                // Auto-execute search if requested (read from args - one-time action flag)
                val workspace = getWorkspace()
                val args = workspace.creationArguments as? SearcherArguments.Default
                if (args?.startSearch == true &&
                    (filenameQuery.value.isNotBlank() || contentQuery.value.isNotBlank())
                ) {
                    log(tag, INFO) { "Auto-starting search from arguments" }
                    performSearch(saveToHistory = true)
                }
            }
            .launchIn(vmScope)

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

        // Observe pending issues/conflicts from operations
        workspaceSource
            .filterNotNull()
            .flatMapLatest { it.operations }
            .map { operationsState ->
                operationsState.pendingConflicts.entries.firstOrNull()
            }
            .onEach { pending ->
                if (pending != null) {
                    log(TAG, INFO) { "Detected pending issue for operation ${pending.key}: ${pending.value}" }
                    issueStateFlow.value = pending.value
                    currentIssueOperationId = pending.key
                } else {
                    issueStateFlow.value = null
                    currentIssueOperationId = null
                }
            }
            .launchIn(vmScope)

        // Auto-search on query text changes with debouncing
        kotlinx.coroutines.flow.combine(filenameQuery, contentQuery) { filename, content ->
            filename to content
        }
            .debounce(1000)
            .distinctUntilChanged()
            .filter { (filename, content) -> filename.isNotBlank() || content.isNotBlank() }
            .filter { (filename, content) -> "$filename|$content" != lastAutoExecutedQuery }
            .onEach { (filename, content) ->
                log(tag, INFO) { "Auto-triggering search for filename: $filename, content: $content" }
                lastAutoExecutedQuery = "$filename|$content"
                performSearch(saveToHistory = false)
            }
            .launchIn(vmScope)

        // Auto-search on target changes (when at least one query exists)
        workspaceSearchState
            .map { it.searchTargets }
            .distinctUntilChanged()
            .drop(1) // Skip initial state to avoid triggering on setup
            .debounce(300) // Short debounce for rapid changes
            .filter { filenameQuery.value.isNotBlank() || contentQuery.value.isNotBlank() }
            .onEach { targets ->
                log(tag, INFO) { "Auto-triggering search due to target change: ${targets.size} targets" }
                performSearch(saveToHistory = false)
            }
            .launchIn(vmScope)

        // Auto-retry search when permissions are granted after setup
        workspaceSearchState
            .map { it.setupRequirements.needsSetup to it.searchStatus }
            .distinctUntilChanged()
            .scan(
                Pair(
                    false to SearcherWorkspace.State.SearchStatus.IDLE,
                    false to SearcherWorkspace.State.SearchStatus.IDLE
                )
            ) { prev, curr ->
                Pair(prev.second, curr)
            }
            .filter { (prev, curr) ->
                // Detect transition from needsSetup=true to needsSetup=false
                val wasNeedingSetup = prev.first
                val noLongerNeedsSetup = !curr.first
                val hadPermissionError = prev.second == SearcherWorkspace.State.SearchStatus.ERROR
                wasNeedingSetup && noLongerNeedsSetup && hadPermissionError
            }
            .filter { filenameQuery.value.isNotBlank() || contentQuery.value.isNotBlank() }
            .onEach {
                log(tag, INFO) { "Permissions granted after setup, auto-retrying search" }
                performSearch(saveToHistory = false)
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

    val clipboard = clipboardRepo.state
        .map { repoState -> ClipboardDisplayState(entries = repoState.entries) }
        .asStateFlow()

    val operations = workspaceSource
        .filterNotNull()
        .flatMapLatest { it.operations }
        .map { opsState -> opsState.operations }
        .toOperationsDisplayState()
        .asStateFlow()

    // Flow for Ready state - only emits when workspace is initialized
    private val readyStateFlow: Flow<State.Ready> = combine(
        filenameQuery,
        contentQuery,
        filenameOptions,
        contentOptions,
        contentSearchEnabled,
        workspaceSearchState,
        searcherSettings.maxHistoryItems.flow.flatMapLatest { searchHistory.getSearches(it) },
        currentFilter,
        selectionState,
        quickActionsResult,
        dialogStateFlow,
        currentSortSettings,
        viewStyleFlow,
        trashSettings.enabled.flow,
    ) { filenameQ: String, contentQ: String, fnOptions: FilenameQuery, ctOptions: ContentQuery, contentSearchOn: Boolean, workspaceState: SearcherWorkspace.State, history: List<SearchHistory.SearchHistoryItem>, filter: SearchFilter, selection: SearcherSelectionState, quickActions: SearchItem?, dialogState: SearcherDialogState, sortSettings: SearchSortSettings, viewStyle: SearcherViewStyle, trashEnabled: Boolean ->
        val sortedResults = itemSorter.sortItems(workspaceState.results, sortSettings)
        val updatedWorkspaceState = workspaceState.copy(results = sortedResults)
        val updatedSelectionState = selection.copy(selectableResults = sortedResults)

        // Calculate available actions based on selection state
        val actions = if (updatedSelectionState.selectedResultIds.isNotEmpty()) {
            buildList {
                // Select All
                if (!updatedSelectionState.isAllSelected && updatedSelectionState.selectableResults.isNotEmpty()) {
                    add(SearcherActionBarItem.SelectAll)
                }

                // Open in New Tabs
                add(SearcherActionBarItem.OpenInNewTabs(updatedSelectionState.selectedResults))

                // Copy
                add(SearcherActionBarItem.Copy(updatedSelectionState.selectedResults))

                // Cut
                add(SearcherActionBarItem.Cut(updatedSelectionState.selectedResults))

                // Share (if reasonable number of items)
                val shareAction = SearcherActionBarItem.Share(updatedSelectionState.selectedResults)
                if (shareAction.isVisible) {
                    add(shareAction)
                }

                // Delete
                add(SearcherActionBarItem.Delete(updatedSelectionState.selectedResults, trashEnabled))
            }
        } else if (sortedResults.isNotEmpty()) {
            buildList {
                add(SearcherActionBarItem.Common.Sort())
                val toggledViewStyle = when (viewStyle) {
                    is SearcherViewStyle.List -> SearcherViewStyle.Grid()
                    is SearcherViewStyle.Grid -> SearcherViewStyle.List()
                }
                add(SearcherActionBarItem.Common.UpdateViewStyle(toggledViewStyle))
            }
        } else {
            emptyList()
        }

        State.Ready(
            filenameQuery = filenameQ,
            contentQuery = contentQ,
            filenameOptions = fnOptions,
            contentOptions = ctOptions,
            contentSearchEnabled = contentSearchOn,
            workspaceState = updatedWorkspaceState,
            searchHistory = history,
            currentFilter = filter,
            searchTargets = workspaceState.searchTargets,
            setupRequirements = workspaceState.setupRequirements,
            selectionState = updatedSelectionState,
            quickActionsResult = quickActions,
            dialogState = dialogState,
            availableActions = actions,
            viewStyle = viewStyle,
            sortSettings = sortSettings,
            trashEnabled = trashEnabled,
        )
    }

    val state: StateFlow<State> = workspaceSource
        .flatMapLatest { workspace ->
            if (workspace == null) {
                flowOf(State.Initializing)
            } else {
                readyStateFlow
            }
        }
        .catch { e ->
            log(tag, ERROR) { "State flow error: ${e.asLog()}" }
            emit(State.Error(e))
        }
        .stateIn(vmScope, SharingStarted.Eagerly, State.Initializing)

    fun restoreFromHistory(item: SearchHistory.SearchHistoryItem) {
        log(TAG, INFO) { "Restoring search from history: ${item.baseQuery}" }
        item.searchQuery?.let { query ->
            // Update all parameters atomically
            filenameQuery.value = query.filenameQuery.pattern
            contentQuery.value = query.contentQuery.pattern

            // Update per-field options (copy the pattern options, not the pattern text)
            filenameOptions.value = query.filenameQuery.copy(pattern = "")
            contentOptions.value = query.contentQuery.copy(pattern = "")

            // Enable content search if the restored query has content
            if (query.contentQuery.isNotEmpty) {
                contentSearchEnabled.value = true
            }

            currentFilter.value = query.filter

            // Update targets
            vmScope.launch {
                val workspace = getWorkspace()
                workspace.updateTargets { query.targets }
            }

            // Clear selection state when restoring from history
            selectionState.value = SearcherSelectionState()

            // Prevent auto-search from double-triggering
            lastAutoExecutedQuery = "${query.filenameQuery.pattern}|${query.contentQuery.pattern}"

            // Explicitly trigger search (don't rely on auto-search)
            performSearch(saveToHistory = false)
        } ?: run {
            // Fallback for legacy history items without full query
            filenameQuery.value = item.baseQuery
            lastAutoExecutedQuery = "${item.baseQuery}|"
            performSearch(saveToHistory = false)
        }
    }

    private fun applyTemplate(template: SearchTemplate) {
        log(TAG, INFO) { "Applying template: ${template.id}" }

        vmScope.launch {
            val workspace = getWorkspace()
            val targets = workspace.state.first().searchTargets

            if (targets.isEmpty()) {
                log(TAG, WARN) { "Cannot apply template: no search targets configured" }
                return@launch
            }

            // Create query from template
            val query = template.createQuery(targets)

            // Update query fields
            filenameQuery.value = query.filenameQuery.pattern
            contentQuery.value = query.contentQuery.pattern

            // Update per-field options
            filenameOptions.value = query.filenameQuery.copy(pattern = "")
            contentOptions.value = query.contentQuery.copy(pattern = "")

            // Enable content search if the template has content query
            if (query.contentQuery.isNotEmpty) {
                contentSearchEnabled.value = true
            }

            // Update filter
            currentFilter.value = query.filter

            // Clear selection state
            selectionState.value = SearcherSelectionState()

            // Prevent auto-search from double-triggering
            lastAutoExecutedQuery = "${query.filenameQuery.pattern}|${query.contentQuery.pattern}"

            // Execute search via workspace command (bypass performSearch to use template's query directly)
            val searchCommand = SearcherCommand.Search(
                filenameQuery = query.filenameQuery,
                contentQuery = query.contentQuery,
                targets = targets,
                filter = query.filter,
                options = SearchQuery.Options(
                    maxResults = searcherSettings.maxSearchResults.value(),
                ),
                saveToHistory = true,
            )
            workspace.execute(searchCommand)
        }
    }

    fun performSearch(saveToHistory: Boolean = false) {
        val filenameText = filenameQuery.value
        val contentText = contentQuery.value

        log(TAG, INFO) { "Performing search: filename=$filenameText, content=$contentText" }

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

            // Build pattern queries with per-field options
            val fnOpts = filenameOptions.value
            val filenameQueryValue = FilenameQuery(
                pattern = filenameText,
                caseSensitive = fnOpts.caseSensitive,
                wholeWord = fnOpts.wholeWord,
                useRegex = fnOpts.useRegex,
            )
            val ctOpts = contentOptions.value
            val contentQueryValue = ContentQuery(
                pattern = contentText,
                caseSensitive = ctOpts.caseSensitive,
                wholeWord = ctOpts.wholeWord,
                useRegex = ctOpts.useRegex,
            )

            // Build search command
            val searchCommand = SearcherCommand.Search(
                filenameQuery = filenameQueryValue,
                contentQuery = contentQueryValue,
                targets = targets,
                filter = currentFilter.value,
                options = SearchQuery.Options(
                    maxResults = searcherSettings.maxSearchResults.value(),
                ),
                saveToHistory = saveToHistory,
            )

            workspace.execute(searchCommand)

            // Record search in history only if explicitly requested
            if (saveToHistory) {
                val searchRequest = SearchQuery(
                    filenameQuery = filenameQueryValue,
                    contentQuery = contentQueryValue,
                    targets = targets,
                    options = searchCommand.options,
                    filter = searchCommand.filter,
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
        filenameQuery.value = ""
        contentQuery.value = ""
        // Clear selection state
        selectionState.value = SearcherSelectionState()
        // Clear workspace state via command
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.execute(SearcherCommand.Clear)
        }
    }

    fun updateFilter(filter: SearchFilter) {
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

    fun selectAllFolders() {
        log(TAG) { "Adding all folders to selection" }
        selectionState.update { state ->
            val folders = state.selectableResults.filterIsInstance<SearchItem.Directory>()
            state.addToSelection(folders)
        }
    }

    fun selectAllFiles() {
        log(TAG) { "Adding all files to selection" }
        selectionState.update { state ->
            val files = state.selectableResults.filterIsInstance<SearchItem.File>()
            state.addToSelection(files)
        }
    }

    fun deselectAll() {
        log(TAG) { "Deselecting all results" }
        selectionState.update { it.deselectAll() }
    }

    fun onAction(action: SearcherActionBarItem) {
        log(TAG) { "Executing action: ${action.javaClass.simpleName}" }

        when (action) {
            is SearcherActionBarItem.Copy -> {
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
            is SearcherActionBarItem.Cut -> {
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
            is SearcherActionBarItem.Delete -> {
                vmScope.launch {
                    val paths = action.results.map { it.path }.toSet()
                    dialogEvents.emit(SearcherDialogEvent.ShowDeleteConfirmation(paths))
                }
            }
            is SearcherActionBarItem.Share -> {
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
            is SearcherActionBarItem.OpenInEditor -> {
                launch {
                    workspaceRemote.createAndFocus(
                        type = Workspace.Type.EDITOR,
                        arguments = EditorArguments.Default(
                            filePath = action.result.path
                        )
                    )
                }
            }
            is SearcherActionBarItem.OpenInExplorer -> {
                launch {
                    if (action.result.path is LocalPath) {
                        val parentPath = (action.result.path as LocalPath).parent
                        if (parentPath != null) {
                            workspaceRemote.createAndFocus(
                                type = Workspace.Type.EXPLORER,
                                arguments = ExplorerArguments.Default(
                                    startPath = parentPath
                                )
                            )
                        }
                    }
                }
            }
            is SearcherActionBarItem.CopyPath -> {
                log(TAG, INFO) { "Copying path to system clipboard: ${action.result.path.path}" }
                systemClipboardHelper.copyToClipboard(action.result.path.path)
            }
            is SearcherActionBarItem.ShowProperties -> {
                dialogStateFlow.value = SearcherDialogState.ShowItemProperties(action.result)
            }
            is SearcherActionBarItem.SelectAll -> selectAll()
            is SearcherActionBarItem.SelectAllFolders -> selectAllFolders()
            is SearcherActionBarItem.SelectAllFiles -> selectAllFiles()
            is SearcherActionBarItem.DeselectAll -> deselectAll()
            is SearcherActionBarItem.Common.Sort -> {
                dialogStateFlow.value = SearcherDialogState.EditSortOptions(
                    currentSortSettings = currentSortSettings.value
                )
            }
            is SearcherActionBarItem.Common.UpdateViewStyle -> {
                viewStyleFlow.value = action.viewStyle
                vmScope.launch {
                    searcherSettings.defaultViewStyle.value(action.viewStyle)
                }
            }
            is SearcherActionBarItem.OpenInNewTabs -> {
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

    fun onActionLongClick(action: SearcherActionBarItem) {
        log(TAG) { "onActionLongClick(${action.javaClass.simpleName})" }
        when (action) {
            is SearcherActionBarItem.Delete -> {
                vmScope.launch {
                    val paths = action.results.map { it.path }.toSet()
                    dialogEvents.emit(
                        SearcherDialogEvent.ShowDeleteConfirmation(
                            paths = paths,
                            forcePermDelete = true,
                        )
                    )
                }
            }
            else -> {
                // Other actions don't support long-press, delegate to regular action
                onAction(action)
            }
        }
    }

    private suspend fun executeOpenInNewTabs(analysis: OpenInNewTabsUseCase.AnalysisResult) {
        log(TAG, INFO) { "executeOpenInNewTabs(): Opening ${analysis.totalOpenableCount} workspaces" }

        // Create workspace requests
        val requests = openInNewTabsUseCase.createRequests(
            analysis = analysis,
            createExplorerArguments = { path -> ExplorerArguments.Default(startPath = path) },
            createEditorArguments = { path -> EditorArguments.Default(filePath = path) },
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
            is WorkspaceAction.CreateBatch.Result.AwaitingConfirmation -> {
                log(TAG, INFO) { "Batch creation awaiting confirmation" }
            }
        }

        deselectAll()
    }

    private fun shareFiles(results: List<SearchItem>) {
        log(TAG) { "shareFiles(): ${results.size} items" }

        val shareItems = results.map { result ->
            object : ShareIntentUseCase.Item {
                override val path = result.path
                override val mimeType = getMimeType(result.name)
                override val displayName = result.name
            }
        }

        val chooserTitle = if (results.size == 1) {
            appContext.getString(eu.darken.butler.common.R.string.general_share_single_title, results.first().name)
        } else {
            appContext.resources.getQuantityString(
                eu.darken.butler.common.R.plurals.general_share_multiple_title,
                results.size,
                results.size
            )
        }

        val success = shareIntentUseCase.shareWithChooser(shareItems, chooserTitle)
        if (!success) {
            throw Exception("Failed to create share intent for selected files")
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

    sealed interface State {
        data object Initializing : State

        data class Error(val error: Throwable) : State

        @Stable
        data class Ready(
            val filenameQuery: String = "",
            val contentQuery: String = "",
            val filenameOptions: FilenameQuery = FilenameQuery(),
            val contentOptions: ContentQuery = ContentQuery(),
            val contentSearchEnabled: Boolean = false,
            val workspaceState: SearcherWorkspace.State = SearcherWorkspace.State(),
            val searchHistory: List<SearchHistory.SearchHistoryItem> = emptyList(),
            val currentFilter: SearchFilter = SearchFilter(),
            val searchTargets: List<SearchTarget> = emptyList(),
            val setupRequirements: PathRequirements = PathRequirements(),
            val selectionState: SearcherSelectionState = SearcherSelectionState(),
            val quickActionsResult: SearchItem? = null,
            val dialogState: SearcherDialogState = SearcherDialogState.None,
            val availableActions: List<SearcherActionBarItem> = emptyList(),
            val viewStyle: SearcherViewStyle = SearcherViewStyle.default(),
            val sortSettings: SearchSortSettings = SearchSortSettings(),
            val trashEnabled: Boolean = false,
        ) : State {
            val isSearching: Boolean
                get() = workspaceState.searchStatus == SearcherWorkspace.State.SearchStatus.SEARCHING

            val isIdle: Boolean
                get() = workspaceState.searchStatus == SearcherWorkspace.State.SearchStatus.IDLE

            val hasResults: Boolean
                get() = workspaceState.results.isNotEmpty()

            val hasActiveFilter: Boolean
                get() = currentFilter.hasConditions()

            val needsSetup: Boolean
                get() = setupRequirements.needsSetup

            val listItems: List<SearchListItem>
                get() = buildList {
                    // Add error item at the top if there's an error
                    // Skip permission errors when setup card is already visible
                    workspaceState.error?.let { error ->
                        val isPermissionError = PermissionErrorClassifier.isPermissionError(error)
                        if (!needsSetup || !isPermissionError) {
                            add(SearchListItem.Error(throwable = error))
                        }
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
    }

    private suspend fun handleDialogEvent(event: SearcherDialogEvent) {
        log(TAG) { "handleDialogEvent($event)" }
        when (event) {
            is SearcherDialogEvent.ShowDeleteConfirmation -> {
                dialogStateFlow.value = SearcherDialogState.DeleteConfirmation(event.paths, event.forcePermDelete)
            }
            is SearcherDialogEvent.Dismiss -> {
                dialogStateFlow.value = SearcherDialogState.None
            }
        }
    }

    fun dismissDialog() {
        dialogStateFlow.value = SearcherDialogState.None
    }

    fun onDeleteConfirmed(items: Set<APath<*>>, forcePermDelete: Boolean = false) = launch {
        log(TAG, INFO) { "onDeleteConfirmed(${items.size} items, forcePermDelete=$forcePermDelete)" }
        dialogStateFlow.value = SearcherDialogState.None

        if (items.isNotEmpty()) {
            getWorkspace().execute(
                SearcherCommand.Delete(
                    targets = items,
                    options = SearcherCommand.Delete.Options(forcePermDelete = forcePermDelete),
                )
            )
            deselectAll()
        }
    }

    fun onSortOptions(result: eu.darken.butler.searcher.ui.search.dialogs.SearchSortOptionsResult) = launch {
        log(tag) { "onSortOptions($result)" }
        dialogStateFlow.value = SearcherDialogState.None
        searcherSettings.defaultSort.value(result.sortSettings)
        currentSortSettings.value = result.sortSettings
    }

    fun onClearHistoryConfirmed() = launch {
        log(tag) { "onClearHistoryConfirmed()" }
        dialogStateFlow.value = SearcherDialogState.None
        searchHistory.clearHistory()
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
                            arguments = ExplorerArguments.Default(startPath = parentPath)
                        )
                    }
                }
            }
            is ClipboardClip.Text -> {
                val sourcePath = clip.sourcePath
                if (sourcePath != null) {
                    val parentPath = sourcePath.parent
                    if (parentPath != null) {
                        workspaceRemote.createAndFocus(
                            type = Workspace.Type.EXPLORER,
                            arguments = ExplorerArguments.Default(startPath = parentPath)
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
                    arguments = ExplorerArguments.Default(startPath = commonParent)
                )
            }
            is ClipboardClip.Text -> {
                log(TAG, WARN) { "Cannot open text clip in Explorer - no file paths" }
                return@launch
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

    fun shareError(id: Operation.Id) = launch {
        log(TAG) { "shareError($id)" }
        val operation = operationsManager.get(id)
        if (operation == null) {
            log(TAG, ERROR) { "Operation with id $id not found" }
            return@launch
        }
        val state = operation.state.value as? Operation.State.Completed ?: return@launch
        val error = state.error ?: return@launch

        val metadata = mapOf<String, String?>(
            "OperationId" to operation.id.toString(),
            "Source" to operation.metadata.origin.toString(),
            "CompletedAt" to state.completedAt.toString(),
        )
        val report = errorReportTool.buildReport(
            throwable = error,
            message = "${operation.metadata.title.get(appContext)}\n${operation.metadata.description.get(appContext)}",
            errorContext = "Operation error in workspace ${this@SearcherWorkspaceViewModel.id.shortTag}",
            metadata = metadata,
        )
        val intent = errorReportTool.createShareChooserIntent(report)
        shareIntentEvent.tryEmit(intent)
    }

    fun showConflictSheet(operationId: Operation.Id) = launch {
        log(TAG) { "showConflictSheet($operationId): Conflict sheet is automatically shown via issueState observation" }
        // Note: Issue sheets are automatically displayed when issueState is set by the init block observer
        // No manual action needed here - the UI observes issueState and shows IssuesBottomSheet when non-null
    }

    fun resolveIssue(resolution: eu.darken.butler.common.files.actions.PathActionIssue.Resolution) = launch {
        val operationId = currentIssueOperationId
        if (operationId != null) {
            log(TAG, INFO) { "Resolving issue for operation $operationId with resolution: $resolution" }
            val workspace = getWorkspace()
            workspace.resolveConflict(operationId, resolution)
        } else {
            log(TAG, WARN) { "Cannot resolve issue: no current issue operation ID" }
        }
    }

    fun shareWorkspaceError() = launch {
        val error = (state.value as? State.Error)?.error ?: return@launch
        log(TAG, INFO) { "Sharing workspace error: ${error.message}" }
        val report = errorReportTool.buildReport(
            throwable = error,
            errorContext = "Workspace initialization failed: ${id.shortTag}",
        )
        val intent = errorReportTool.createShareChooserIntent(report)
        shareIntentEvent.tryEmit(intent)
    }

    fun closeWorkspace() = launch {
        log(TAG, INFO) { "Closing workspace $id" }
        workspaceRemote.execute(WorkspaceAction.Close(id))
    }

    /**
     * Unified handler for all page-level actions.
     * Dispatches to appropriate ViewModel methods based on action type.
     */
    fun onPageAction(action: SearcherPageAction) {
        log(TAG, INFO) { "onPageAction(): $action" }
        when (action) {
            // Search actions
            is SearcherPageAction.Search.UpdateFilenameQuery -> {
                log(TAG, INFO) { "Updating filename query: ${action.text}" }
                filenameQuery.value = action.text
                // Auto-clear results when both queries become empty
                if (action.text.isBlank() && contentQuery.value.isBlank()) {
                    clearResults()
                }
            }
            is SearcherPageAction.Search.UpdateContentQuery -> {
                log(TAG, INFO) { "Updating content query: ${action.text}" }
                contentQuery.value = action.text
                // Auto-clear results when both queries become empty
                if (action.text.isBlank() && filenameQuery.value.isBlank()) {
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

            // Filename pattern options (workspace-local, not saved to settings)
            is SearcherPageAction.Options.ToggleFilenameCaseSensitive -> {
                filenameOptions.update { it.copy(caseSensitive = !it.caseSensitive) }
                performSearch(saveToHistory = false)
            }
            is SearcherPageAction.Options.ToggleFilenameWholeWord -> {
                filenameOptions.update { it.copy(wholeWord = !it.wholeWord) }
                performSearch(saveToHistory = false)
            }
            is SearcherPageAction.Options.ToggleFilenameRegex -> {
                filenameOptions.update { it.copy(useRegex = !it.useRegex) }
                performSearch(saveToHistory = false)
            }

            // Content pattern options (workspace-local, not saved to settings)
            is SearcherPageAction.Options.ToggleContentCaseSensitive -> {
                contentOptions.update { it.copy(caseSensitive = !it.caseSensitive) }
                performSearch(saveToHistory = false)
            }
            is SearcherPageAction.Options.ToggleContentWholeWord -> {
                contentOptions.update { it.copy(wholeWord = !it.wholeWord) }
                performSearch(saveToHistory = false)
            }
            is SearcherPageAction.Options.ToggleContentRegex -> {
                contentOptions.update { it.copy(useRegex = !it.useRegex) }
                performSearch(saveToHistory = false)
            }
            is SearcherPageAction.Options.ToggleContentSearch -> {
                contentSearchEnabled.value = !contentSearchEnabled.value
                performSearch(saveToHistory = false)
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
            is SearcherPageAction.History.ShowClearDialog -> {
                dialogStateFlow.value = SearcherDialogState.ClearHistoryConfirmation
            }
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

            // Templates
            is SearcherPageAction.Templates.Apply -> applyTemplate(action.template)

            // Filter - Condition-based actions
            is SearcherPageAction.Filter.OpenSizeConditionEditor -> {
                dialogStateFlow.value = SearcherDialogState.EditSizeCondition(existing = null)
            }
            is SearcherPageAction.Filter.OpenDateConditionEditor -> {
                dialogStateFlow.value = SearcherDialogState.EditDateCondition(existing = null)
            }
            is SearcherPageAction.Filter.OpenTypeConditionEditor -> {
                dialogStateFlow.value = SearcherDialogState.EditTypeCondition(existing = null)
            }
            is SearcherPageAction.Filter.AddCondition -> {
                currentFilter.value = currentFilter.value.withCondition(action.condition)
                selectionState.value = SearcherSelectionState()
                dismissDialog()
                performSearch(saveToHistory = false)
            }
            is SearcherPageAction.Filter.RemoveCondition -> {
                currentFilter.value = currentFilter.value.copy(
                    conditions = currentFilter.value.conditions - action.condition
                )
                performSearch(saveToHistory = false)
            }
            is SearcherPageAction.Filter.EditCondition -> {
                when (action.condition) {
                    is FilterCondition.Size -> {
                        dialogStateFlow.value = SearcherDialogState.EditSizeCondition(
                            existing = action.condition
                        )
                    }
                    is FilterCondition.ModifiedDate -> {
                        dialogStateFlow.value = SearcherDialogState.EditDateCondition(
                            existing = action.condition
                        )
                    }
                    is FilterCondition.Type -> {
                        dialogStateFlow.value = SearcherDialogState.EditTypeCondition(
                            existing = action.condition
                        )
                    }
                }
            }

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
            is SearcherPageAction.Error.Share -> {
                log(TAG) { "shareSearchError(${action.error.javaClass.simpleName})" }
                val report = errorReportTool.buildReport(
                    throwable = action.error,
                    errorContext = "Search operation in workspace ${id.shortTag}",
                )
                val intent = errorReportTool.createShareChooserIntent(report)
                shareIntentEvent.tryEmit(intent)
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
