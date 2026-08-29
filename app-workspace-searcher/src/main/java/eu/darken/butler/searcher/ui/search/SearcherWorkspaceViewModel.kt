package eu.darken.butler.searcher.ui.search

import android.content.Context
import androidx.compose.runtime.Stable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ApiLevel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.searcher.core.resultKey
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorIncidentFactory
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.extensions.commonParent
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.searcher.core.SearchTemplate
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.searcher.core.sorting.SearchItemSorter
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogEvent
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogState
import eu.darken.butler.searcher.ui.search.util.SearchListItem
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.searcher.ui.search.util.SearcherSelectionState
import eu.darken.butler.searcher.ui.search.util.toOpenInNewTabsItem
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.NoAppForFileException
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.OpenWithIntentUseCase
import eu.darken.butler.workspace.core.ShareIntentUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.preview.FolderPreviewObserver
import eu.darken.butler.workspace.core.preview.FolderPreviewResolver
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
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
    private val workspaceRemote: WorkspaceRemote,
    private val workspaceProvider: WorkspaceProvider,
    private val openInNewTabsUseCase: OpenInNewTabsUseCase,
    private val shareIntentUseCase: ShareIntentUseCase,
    private val openWithIntentUseCase: OpenWithIntentUseCase,
    private val trashSettings: TrashSettings,
    private val folderPreviewResolver: FolderPreviewResolver,
    private val apiLevel: ApiLevel,
    private val errorIncidentFactory: ErrorIncidentFactory,
    itemSorterFactory: SearchItemSorter.Factory,
    chromeFactory: WorkspacePageChrome.Factory,
) : ViewModel4(dispatchers, logTag("Searcher", "Workspace", id.shortTag, "Page")) {

    private val itemSorter = itemSorterFactory.create(id)
    private val chrome = chromeFactory.create(id, vmScope)

    val folderPreviewObserver: FolderPreviewObserver = folderPreviewResolver::observe

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

    // Overlay visibility lives here rather than in the page: the page and its overlays are
    // siblings, so a `remember` in the page would be a different instance from the one the
    // overlays read. The retained throwable is never persisted — it is cleared on dismiss/share
    // and the same instance is already held by the search engine's target progress state.
    private val _overlayState = MutableStateFlow(OverlayState())
    val overlayState: StateFlow<OverlayState> = _overlayState

    // Issue/conflict handling
    private val conflicts = SearcherOperationConflictController(
        pendingConflicts = chrome.pendingConflicts,
        workspace = ::getWorkspace,
        doLaunch = { block -> launch(block = block) },
        tag = TAG,
    )
    val issueState = conflicts.issueState

    val dialogEvents = SingleEventFlow<SearcherDialogEvent>()

    val shareIntentEvent = chrome.shareIntentEvent

    val pendingErrorShare = chrome.pendingErrorShare

    // Observe workspace search state
    private val workspaceSearchState: Flow<SearcherWorkspace.State> = workspaceSource
        .filterNotNull()
        .flatMapLatest { it.state }

    // Results arrive already deduplicated by normalized path from the workspace's
    // ResultAccumulator (which also resolves source-rank replacements); this layer only sorts.
    // The workspace only publishes a NEW list instance when results actually change (once per
    // batch), so memoizing on list identity plus sort settings means progress-only state
    // emissions don't re-run sorting.
    @Volatile
    private var displayResultsCache: Triple<List<SearchItem>, SearchSortSettings, List<SearchItem>>? = null

    private fun displayResults(raw: List<SearchItem>, sort: SearchSortSettings): List<SearchItem> {
        displayResultsCache?.let { (cachedRaw, cachedSort, cachedValue) ->
            if (cachedRaw === raw && cachedSort == sort) return cachedValue
        }
        // Results arrive path-deduplicated from the workspace (dedup runs before the result cap)
        val computed = itemSorter.sortItems(raw, sort)
        displayResultsCache = Triple(raw, sort, computed)
        return computed
    }

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
                        // Add each selected path, deduplicating against existing path targets only
                        // (a null-keyed distinctBy would collapse all non-Path targets into one)
                        result.selectedPaths.forEach { path ->
                            workspace.updateTargets { current ->
                                val exists = current.any { it is SearchTarget.Path && it.path == path }
                                if (exists) current else current + SearchTarget.Path.from(path)
                            }
                        }
                    }
                }
            }
            .launchIn(vmScope)

        // Observe pending issues/conflicts from operations
        conflicts.conflictObserver.launchIn(vmScope)

        // Auto-search on query text changes with debouncing
        kotlinx.coroutines.flow.combine(filenameQuery, contentQuery) { filename, content ->
            filename to content
        }
            .debounce(1000)
            .distinctUntilChanged()
            .filter { (filename, content) ->
                filename.isNotBlank() || (contentSearchEnabled.value && content.isNotBlank())
            }
            // Key on the EFFECTIVE query: a disabled hidden content pattern must not make two
            // visually identical searches look different (or identical ones look the same)
            .filter { (filename, content) -> autoSearchKey(filename, content) != lastAutoExecutedQuery }
            .onEach { (filename, content) ->
                log(tag, INFO) { "Auto-triggering search for filename: $filename, content: $content" }
                lastAutoExecutedQuery = autoSearchKey(filename, content)
                performSearch(saveToHistory = false)
            }
            .launchIn(vmScope)

        // Auto-search on target changes (when at least one query exists)
        workspaceSearchState
            .map { it.searchTargets }
            .distinctUntilChanged()
            .drop(1) // Skip initial state to avoid triggering on setup
            .debounce(300) // Short debounce for rapid changes
            .filter { filenameQuery.value.isNotBlank() || effectiveContentText().isNotBlank() }
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
            .filter { hasExecutableSearch() }
            .onEach {
                log(tag, INFO) { "Permissions granted after setup, auto-retrying search" }
                performSearch(saveToHistory = false)
            }
            .launchIn(vmScope)

        // Auto-rerun when access to previously unreadable items is granted after setup. Unlike the
        // block above (a search refused outright, status ERROR), this covers a search that
        // COMPLETED but skipped protected items (e.g. Android/data before root was enabled).
        // The trigger is a NEEDS_SETUP -> SATISFIED edge: a new search resets the requirements to
        // empty combos (NONE), which is deliberately NOT a trigger — only an actual grant keeps
        // the combos and flips one complete. This keeps new-search resets and rerun results
        // (which either clear the errors or return to NEEDS_SETUP) from re-triggering.
        workspaceSearchState
            .map { it.accessErrorRequirements.toAccessSetupPhase() to it.searchStatus }
            .distinctUntilChanged()
            .scan(
                Pair(
                    AccessSetupPhase.NONE to SearcherWorkspace.State.SearchStatus.IDLE,
                    AccessSetupPhase.NONE to SearcherWorkspace.State.SearchStatus.IDLE,
                )
            ) { prev, curr ->
                Pair(prev.second, curr)
            }
            .filter { (prev, curr) ->
                prev.first == AccessSetupPhase.NEEDS_SETUP &&
                    prev.second == SearcherWorkspace.State.SearchStatus.COMPLETED &&
                    curr.first == AccessSetupPhase.SATISFIED &&
                    curr.second == SearcherWorkspace.State.SearchStatus.COMPLETED
            }
            .filter { hasExecutableSearch() }
            .onEach {
                log(tag, INFO) { "Access granted for previously unreadable items, auto-retrying search" }
                performSearch(saveToHistory = false)
            }
            .launchIn(vmScope)

        // Update history with result counts when search completes
        workspaceSearchState
            .onEach { wsState ->
                val displayed = displayResults(wsState.results, currentSortSettings.value)
                when (wsState.searchStatus) {
                    SearcherWorkspace.State.SearchStatus.COMPLETED -> currentSearchId?.let { id ->
                        searchHistory.updateResultCount(id, displayed.size)
                        currentSearchId = null
                    }
                    // A failed or aborted search must not leave a pending id behind that a later
                    // search's completion could mistakenly consume
                    SearcherWorkspace.State.SearchStatus.ERROR,
                    SearcherWorkspace.State.SearchStatus.CANCELLED,
                        -> currentSearchId = null

                    else -> Unit
                }

                // Update selection state when results change; drop selections (and the quick
                // actions target) for results that no longer exist, e.g. deleted externally.
                // Identity is the normalized resultKey so a rank replacement (possibly with an
                // alias-spelled path) keeps selections alive.
                val visibleIds = displayed.mapTo(mutableSetOf()) { it.resultKey }
                selectionState.update { selection ->
                    selection.copy(
                        selectableResults = displayed,
                        selectedResultIds = selection.selectedResultIds.filterTo(mutableSetOf()) { it in visibleIds },
                    )
                }
                // Rebind (not just retain): a higher-ranked duplicate may have REPLACED the item
                // instance the sheet was opened with (see ResultAccumulator)
                quickActionsResult.update { current ->
                    current?.let { cur -> displayed.firstOrNull { it.resultKey == cur.resultKey } }
                }
            }
            .launchIn(vmScope)
    }

    val clipboard = chrome.clipboard.asStateFlow()

    val operations = chrome.operations.asStateFlow()

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
        val sortedResults = displayResults(workspaceState.results, sortSettings)
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
            selectionState = updatedSelectionState,
            quickActionsResult = quickActions,
            dialogState = dialogState,
            availableActions = actions,
            viewStyle = viewStyle,
            sortSettings = sortSettings,
            trashEnabled = trashEnabled,
            addableMediaCollections = SearchTarget.MediaStore.Collection.entries.filter { collection ->
                apiLevel.has(collection.minApiLevel) &&
                    workspaceState.searchTargets.none { it.identity == collection }
            },
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

    private fun restoreFromHistory(item: SearchHistory.SearchHistoryItem) {
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

            // Execute the template's query directly so its patterns and filters apply verbatim
            executeSearchQuery(workspace, query, saveToHistory = true)
        }
    }

    // Content search only participates while its toggle is enabled; the typed pattern stays in
    // contentQuery so re-enabling the toggle restores it.
    private fun effectiveContentText(): String = if (contentSearchEnabled.value) contentQuery.value else ""

    private fun autoSearchKey(filename: String, content: String): String =
        "$filename|${if (contentSearchEnabled.value) content else ""}"

    private fun hasExecutableSearch(): Boolean = filenameQuery.value.isNotBlank() ||
        effectiveContentText().isNotBlank() ||
        currentFilter.value.hasConditions()

    // Clears executed results and selection but keeps the entered query text
    private fun clearExecutedResults() {
        selectionState.value = SearcherSelectionState()
        // Re-running the same query after a clear must not be suppressed by auto-search dedupe
        lastAutoExecutedQuery = null
        vmScope.launch {
            getWorkspace().execute(SearcherCommand.Clear)
        }
    }

    private fun performSearch(saveToHistory: Boolean = false) {
        // Snapshot all inputs before launching: the coroutine runs on Default, and a rapid
        // toggle/option change must not make the guard and the assembled query disagree
        val filenameText = filenameQuery.value
        val rawContentText = contentQuery.value
        val contentEnabled = contentSearchEnabled.value
        val filter = currentFilter.value
        val fnOptions = filenameOptions.value
        val ctOptions = contentOptions.value
        val effectiveContent = if (contentEnabled) rawContentText else ""

        if (filenameText.isBlank() && effectiveContent.isBlank() && !filter.hasConditions()) {
            log(TAG, INFO) { "Nothing to search for (no patterns, no filters) - clearing results" }
            clearExecutedResults()
            return
        }

        log(TAG, INFO) { "Performing search: filename=$filenameText, content=$effectiveContent" }

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
            val (filenameQueryValue, contentQueryValue) = buildQueries(
                filenameText = filenameText,
                contentText = rawContentText,
                contentSearchEnabled = contentEnabled,
                filenameOptions = fnOptions,
                contentOptions = ctOptions,
            )

            executeSearchQuery(
                workspace = workspace,
                query = SearchQuery(
                    filenameQuery = filenameQueryValue,
                    contentQuery = contentQueryValue,
                    targets = targets,
                    filter = filter,
                ),
                saveToHistory = saveToHistory,
            )
        }
    }

    // Single execution path for assembled queries (user input and templates): applies the
    // configured result limit, records history, then starts the workspace search.
    private suspend fun executeSearchQuery(
        workspace: SearcherWorkspace,
        query: SearchQuery,
        saveToHistory: Boolean,
    ) {
        // A new search supersedes any still-pending history id, saved or not - otherwise this
        // search's completion could update the previous search's history row
        currentSearchId = null

        val finalQuery = query.copy(
            options = query.options.copy(maxResults = searcherSettings.maxSearchResults.value()),
        )

        // Record history BEFORE executing - a fast search could complete before the id is set,
        // which would lose the result count. A history failure must not block the search itself.
        if (saveToHistory && searcherSettings.saveHistory.value()) {
            currentSearchId = try {
                searchHistory.addSearch(finalQuery)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to record search in history: ${e.asLog()}" }
                null
            }
        }

        workspace.execute(
            SearcherCommand.Search(
                filenameQuery = finalQuery.filenameQuery,
                contentQuery = finalQuery.contentQuery,
                targets = finalQuery.targets,
                filter = finalQuery.filter,
                options = finalQuery.options,
            )
        )
    }

    private fun cancelSearch() {
        log(TAG) { "Cancelling search" }
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.execute(SearcherCommand.Cancel)
        }
    }

    private fun clearResults() {
        log(TAG) { "Clearing search results" }
        filenameQuery.value = ""
        contentQuery.value = ""
        clearExecutedResults()
    }

    // Targets are matched by identity-relevant fields (path/collection), not equality —
    // mutable state like `enabled` must not affect matching.
    private fun SearchTarget.matchesIdentity(other: SearchTarget): Boolean = identity == other.identity

    private fun addMediaStoreTarget(collection: SearchTarget.MediaStore.Collection) {
        log(TAG) { "Adding MediaStore target: $collection" }
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.updateTargets { current ->
                val target = SearchTarget.MediaStore(collection)
                if (current.any { it.matchesIdentity(target) }) current else current + target
            }
        }
    }

    private fun removeSearchTarget(target: SearchTarget) {
        log(TAG) { "Removing search target: ${target.displayText}" }
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.updateTargets { current ->
                current.filter { !it.matchesIdentity(target) }
            }
        }
    }

    private fun toggleTargetEnabled(target: SearchTarget) {
        log(TAG) { "Toggling target enabled: ${target.displayText}" }
        vmScope.launch {
            val workspace = getWorkspace()
            workspace.updateTargets { current ->
                current.map {
                    if (it.matchesIdentity(target)) {
                        when (it) {
                            is SearchTarget.Path -> it.copy(enabled = !it.enabled)
                            is SearchTarget.MediaStore -> it.copy(enabled = !it.enabled)
                        }
                    } else it
                }
            }
        }
    }

    // Selection and action methods
    private fun hideQuickActions() {
        quickActionsResult.value = null
    }

    private fun enterSelectionMode(result: SearchItem) {
        log(TAG) { "Entering selection mode with: ${result.path}" }
        selectionState.update { it.enterSelectionMode(result) }
        hideQuickActions()
    }

    private fun toggleSelection(result: SearchItem) {
        log(TAG) { "Toggling selection for: ${result.path}" }
        selectionState.update { it.toggleSelection(result) }
    }

    private fun setSelection(resultIds: Set<String>) {
        log(TAG) { "Setting selection to ${resultIds.size} results" }
        selectionState.update { it.setSelection(resultIds) }
    }

    private fun selectAll() {
        log(TAG) { "Selecting all results" }
        selectionState.update { it.selectAll() }
    }

    private fun selectAllFolders() {
        log(TAG) { "Adding all folders to selection" }
        selectionState.update { state ->
            val folders = state.selectableResults.filterIsInstance<SearchItem.Directory>()
            state.addToSelection(folders)
        }
    }

    private fun selectAllFiles() {
        log(TAG) { "Adding all files to selection" }
        selectionState.update { state ->
            val files = state.selectableResults.filterIsInstance<SearchItem.File>()
            state.addToSelection(files)
        }
    }

    private fun deselectAll() {
        log(TAG) { "Deselecting all results" }
        selectionState.update { it.deselectAll() }
    }

    private fun onAction(action: SearcherActionBarItem) {
        log(TAG) { "Executing action: ${action.javaClass.simpleName}" }

        when (action) {
            is SearcherActionBarItem.Copy -> {
                vmScope.launch {
                    clipboardRepo.add(
                        ClipboardClip.Paths(
                            origin = id,
                            mode = ClipboardClip.Paths.Mode.COPY,
                            paths = action.results.map { it.lookup }
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
                            paths = action.results.map { it.lookup }
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
            is SearcherActionBarItem.Open -> {
                launch {
                    // The viewer opens as a drill-down of this workspace: an overlay in the same
                    // pane that returns here on back. Text files still go to the Editor as a tab.
                    openResult(result = action.result, asDrillDown = true)
                }
            }
            is SearcherActionBarItem.OpenInTab -> {
                launch { openResult(result = action.result, asDrillDown = false) }
            }
            is SearcherActionBarItem.OpenWith -> {
                launch {
                    val path = action.result.path
                    val launched = openWithIntentUseCase.openWithChooser(
                        path = path,
                        mime = MimeInfo.fromFileName(action.result.name).rawType,
                        chooserTitle = appContext.getString(
                            eu.darken.butler.workspace.R.string.workspace_open_with_chooser_title
                        ),
                    )
                    if (!launched) {
                        log(TAG, WARN) { "No other app found to open file: ${action.result.name}" }
                        errorEvents.emit(NoAppForFileException(action.result.name))
                    }
                }
            }
            is SearcherActionBarItem.OpenInEditor -> {
                launch {
                    workspaceRemote.createAndFocus(
                        type = Workspace.Type.EDITOR,
                        arguments = EditorArguments.Default(
                            filePath = action.result.path
                        ),
                        sourceWorkspaceId = id,
                    )
                }
            }
            is SearcherActionBarItem.OpenInExplorer -> {
                launch {
                    val startPath = if (action.result.fileType == FileType.DIRECTORY) {
                        action.result.path
                    } else {
                        action.result.path.parent
                    }
                    if (startPath != null) {
                        workspaceRemote.createAndFocus(
                            type = Workspace.Type.EXPLORER,
                            arguments = ExplorerArguments.Default(
                                startPath = startPath,
                            ),
                            sourceWorkspaceId = id,
                        )
                    }
                }
            }
            is SearcherActionBarItem.CopyPath -> {
                log(TAG, INFO) { "Copying path to system clipboard: ${action.result.path.path}" }
                chrome.copyToSystemClipboard(action.result.path.path)
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

                    val request = OpenInNewTabsUseCase.Request(
                        items = action.results.map { it.toOpenInNewTabsItem() },
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

    /**
     * Routes a single result to the workspace type that fits it - the same classification the
     * multi-select path uses, so a text file reaches the Editor instead of a Viewer that can only
     * say it does not support the type.
     *
     * [asDrillDown] only affects the Viewer: it is the one target whose whole content is this file,
     * so it can live as an overlay in this pane. The Editor always opens as a tab of its own.
     */
    private suspend fun openResult(result: SearchItem, asDrillDown: Boolean) {
        val request = openInNewTabsUseCase.createRequest(
            item = result.toOpenInNewTabsItem(),
            createExplorerArguments = { ExplorerArguments.Default(startPath = it) },
            createEditorArguments = { EditorArguments.Default(filePath = it) },
            createViewerArguments = {
                ViewerArguments.Default(
                    filePath = it,
                    callerWorkspaceId = if (asDrillDown) id else null,
                )
            },
        )
        workspaceRemote.createAndFocus(
            type = request.type,
            arguments = request.arguments,
            sourceWorkspaceId = id,
        )
    }

    private suspend fun executeOpenInNewTabs(analysis: OpenInNewTabsUseCase.AnalysisResult) {
        log(TAG, INFO) { "executeOpenInNewTabs(): Opening ${analysis.totalOpenableCount} workspaces" }

        // Create workspace requests
        val requests = openInNewTabsUseCase.createRequests(
            analysis = analysis,
            createExplorerArguments = { path -> ExplorerArguments.Default(startPath = path) },
            createEditorArguments = { path -> EditorArguments.Default(filePath = path) },
            createViewerArguments = { path -> ViewerArguments.Default(filePath = path) },
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
                override val mimeType = MimeInfo.fromFileName(result.name).rawType
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
            val selectionState: SearcherSelectionState = SearcherSelectionState(),
            val quickActionsResult: SearchItem? = null,
            val dialogState: SearcherDialogState = SearcherDialogState.None,
            val availableActions: List<SearcherActionBarItem> = emptyList(),
            val viewStyle: SearcherViewStyle = SearcherViewStyle.default(),
            val sortSettings: SearchSortSettings = SearchSortSettings(),
            val trashEnabled: Boolean = false,
            val addableMediaCollections: List<SearchTarget.MediaStore.Collection> = emptyList(),
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
                get() = workspaceState.setupRequirements.needsSetup

            // lazy: computed once per State.Ready instance instead of on every Compose read
            val listItems: List<SearchListItem> by lazy {
                buildList {
                    // Add error item at the top if there's an error
                    // Skip permission errors when setup card is already visible
                    workspaceState.error?.let { error ->
                        val isPermissionError = PermissionErrorClassifier.isPermissionError(error)
                        if (!needsSetup || !isPermissionError) {
                            add(SearchListItem.Error(throwable = error))
                        }
                    }

                    // Results are already deduplicated by absolute path upstream (see
                    // displayResults), so keying the LazyColumn by path is safe here.
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
    }

    private suspend fun handleDialogEvent(event: SearcherDialogEvent) {
        log(TAG) { "handleDialogEvent($event)" }
        when (event) {
            is SearcherDialogEvent.ShowDeleteConfirmation -> {
                dialogStateFlow.value = SearcherDialogState.DeleteConfirmation(
                    paths = event.paths,
                    initialPermanentDelete = event.initialPermanentDelete,
                )
            }
            is SearcherDialogEvent.Dismiss -> {
                dialogStateFlow.value = SearcherDialogState.None
            }
        }
    }

    private fun dismissDialog() {
        dialogStateFlow.value = SearcherDialogState.None
    }

    private fun onDeleteConfirmed(items: Set<APath<*>>, forcePermDelete: Boolean = false) = launch {
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

    private fun onSortOptions(result: eu.darken.butler.searcher.ui.search.dialogs.SearchSortOptionsResult) = launch {
        log(tag) { "onSortOptions($result)" }
        dialogStateFlow.value = SearcherDialogState.None
        searcherSettings.defaultSort.value(result.sortSettings)
        currentSortSettings.value = result.sortSettings
    }

    private fun onClearHistoryConfirmed() = launch {
        log(tag) { "onClearHistoryConfirmed()" }
        dialogStateFlow.value = SearcherDialogState.None
        searchHistory.clearHistory()
    }

    private fun navigateToClipboardSource(clip: ClipboardClip) = launch {
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
                            arguments = ExplorerArguments.Default(startPath = parentPath),
                            sourceWorkspaceId = id,
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
                            arguments = ExplorerArguments.Default(startPath = parentPath),
                            sourceWorkspaceId = id,
                        )
                    }
                }
            }
        }
    }

    private fun openClipboardInExplorer(clip: ClipboardClip) = launch {
        log(TAG) { "openClipboardInExplorer($clip)" }

        when (clip) {
            is ClipboardClip.Paths -> {
                if (clip.paths.isEmpty()) {
                    log(TAG, WARN) { "Cannot open in Explorer - clipboard has no paths" }
                    return@launch
                }

                val commonParent = clip.paths.map { it.lookedUp }.commonParent()
                if (commonParent == null) {
                    log(TAG, WARN) { "Cannot open in Explorer - paths have no common parent" }
                    return@launch
                }

                log(TAG) { "Opening Explorer at common parent: $commonParent" }
                workspaceRemote.createAndFocus(
                    type = Workspace.Type.EXPLORER,
                    arguments = ExplorerArguments.Default(startPath = commonParent),
                    sourceWorkspaceId = id,
                )
            }
            is ClipboardClip.Text -> {
                log(TAG, WARN) { "Cannot open text clip in Explorer - no file paths" }
                return@launch
            }
        }
    }

    private fun copyPathToSystemClipboard(text: String) {
        log(TAG) { "copyPathToSystemClipboard($text)" }
        chrome.copyToSystemClipboard(text)
    }

    private fun removeClipboardEntry(clip: ClipboardClip) {
        log(TAG) { "removeClipboardEntry($clip)" }
        chrome.removeClipboardEntry(clip)
        dismissDialog()
    }

    private fun showConflictSheet(operationId: Operation.Id) = launch {
        log(TAG) { "showConflictSheet($operationId): Conflict sheet is automatically shown via issueState observation" }
        // Note: Issue sheets are automatically displayed when issueState is set by the init block observer
        // No manual action needed here - the UI observes issueState and shows IssuesBottomSheet when non-null
    }

    private fun resolveIssue(resolution: eu.darken.butler.common.files.actions.PathActionIssue.Resolution) =
        conflicts.resolve(resolution)

    /**
     * Unified handler for all page-level actions.
     * Dispatches to appropriate ViewModel methods based on action type.
     */
    private fun shareSearchError(action: SearcherPageAction.Error.Share) = launch {
        log(TAG) { "shareSearchError(${action.error.javaClass.simpleName})" }
        val searchState = workspaceSearchState.first()
        val incident = errorIncidentFactory.freeze(
            error = action.error,
            context = mapOf(
                "search.query" to searchState.currentSearchQuery?.toString(),
                "search.targets" to searchState.searchTargets.joinToString(", "),
                "search.targetPath" to action.targetPath,
            ),
        )
        chrome.shareWorkspaceError(incident)
    }

    fun onPageAction(action: SearcherPageAction) {
        log(TAG, INFO) { "onPageAction(): $action" }
        when (action) {
            // Search actions
            is SearcherPageAction.Search.UpdateFilenameQuery -> {
                log(TAG, INFO) { "Updating filename query: ${action.text}" }
                filenameQuery.value = action.text
                // Auto-clear results when both queries become effectively empty
                if (action.text.isBlank() && effectiveContentText().isBlank()) {
                    clearExecutedResults()
                }
            }
            is SearcherPageAction.Search.UpdateContentQuery -> {
                log(TAG, INFO) { "Updating content query: ${action.text}" }
                contentQuery.value = action.text
                // Auto-clear results when both queries become empty
                if (action.text.isBlank() && filenameQuery.value.isBlank()) {
                    clearExecutedResults()
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
            is SearcherPageAction.Targets.AddMediaStore -> addMediaStoreTarget(action.collection)
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
            is SearcherPageAction.Results.SetSelection -> setSelection(action.resultIds)
            is SearcherPageAction.Results.ExitSelectionMode -> deselectAll()
            is SearcherPageAction.Results.HideQuickActions -> hideQuickActions()

            // Clipboard
            is SearcherPageAction.Clipboard.ClickEntry -> {
                log(TAG) { "showClipboardInfo(${action.clip})" }
                dialogStateFlow.value = SearcherDialogState.ClipboardInfo(action.clip)
            }
            is SearcherPageAction.Clipboard.RemoveEntry -> removeClipboardEntry(action.clip)
            is SearcherPageAction.Clipboard.ClearAll -> chrome.clearClipboard()
            is SearcherPageAction.Clipboard.NavigateToSource -> navigateToClipboardSource(action.clip)
            is SearcherPageAction.Clipboard.OpenInExplorer -> openClipboardInExplorer(action.clip)
            is SearcherPageAction.Clipboard.CopyText -> copyPathToSystemClipboard(action.text)

            // Operations
            is SearcherPageAction.Operations.Cancel -> chrome.cancelOperation(action.id)
            is SearcherPageAction.Operations.Dismiss -> chrome.dismissOperation(action.id)
            is SearcherPageAction.Operations.ClearCompleted -> chrome.clearCompletedOperations()
            is SearcherPageAction.Operations.ShareError -> chrome.shareOperationError(action.id)
            is SearcherPageAction.Operations.ShowConflict -> showConflictSheet(action.id)

            // Dialogs
            is SearcherPageAction.Dialogs.Dismiss -> dismissDialog()
            is SearcherPageAction.Dialogs.DeleteConfirmed -> onDeleteConfirmed(action.paths, action.forcePermDelete)
            is SearcherPageAction.Dialogs.SortOptionsConfirmed -> onSortOptions(action.result)
            is SearcherPageAction.Dialogs.ClearHistoryConfirmed -> onClearHistoryConfirmed()

            // Issues
            is SearcherPageAction.Issues.Resolve -> resolveIssue(action.resolution)

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
            is SearcherPageAction.Error.Share -> shareSearchError(action)
            is SearcherPageAction.Error.ConfirmShare -> chrome.confirmErrorShare()
            is SearcherPageAction.Error.DismissShare -> chrome.dismissErrorShare()

            // Overlays
            is SearcherPageAction.Overlays.ShowTemplates -> {
                _overlayState.update { it.copy(showTemplatesSheet = true) }
            }
            is SearcherPageAction.Overlays.DismissTemplates -> {
                _overlayState.update { it.copy(showTemplatesSheet = false) }
            }
            is SearcherPageAction.Overlays.ShowAccessErrors -> {
                _overlayState.update { it.copy(showAccessErrorsSheet = true) }
            }
            is SearcherPageAction.Overlays.DismissAccessErrors -> {
                _overlayState.update { it.copy(showAccessErrorsSheet = false) }
            }
            is SearcherPageAction.Overlays.ShowOperationDetails -> {
                _overlayState.update {
                    it.copy(operationDialogState = OperationDialogState.OperationDetails(action.id))
                }
            }
            is SearcherPageAction.Overlays.DismissOperationDetails -> {
                _overlayState.update { it.copy(operationDialogState = OperationDialogState.None) }
            }
            is SearcherPageAction.Overlays.RequestCancelOperation -> {
                _overlayState.update {
                    it.copy(
                        operationDialogState = OperationDialogState.None,
                        cancelOperationConfirmationFor = action.id,
                    )
                }
            }
            is SearcherPageAction.Overlays.DismissCancelOperation -> {
                _overlayState.update { it.copy(cancelOperationConfirmationFor = null) }
            }
            is SearcherPageAction.Overlays.ShowTargetError -> {
                _overlayState.update { it.copy(targetError = TargetError(action.path, action.error)) }
            }
            is SearcherPageAction.Overlays.DismissTargetError -> {
                _overlayState.update { it.copy(targetError = null) }
            }

            // Workspace actions (delegate to existing handler)
            is SearcherPageAction.WorkspaceAction -> onAction(action.action)
        }
    }

    data class TargetError(val path: String, val error: Throwable)

    data class OverlayState(
        val showTemplatesSheet: Boolean = false,
        val showAccessErrorsSheet: Boolean = false,
        val operationDialogState: OperationDialogState = OperationDialogState.None,
        val cancelOperationConfirmationFor: Operation.Id? = null,
        val targetError: TargetError? = null,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SearcherWorkspaceViewModel
    }

    companion object {
        private val TAG = logTag("Searcher", "Workspace", "ViewModel")

        // Assembles the executed pattern queries; the content pattern only applies while the
        // content-search toggle is enabled.
        internal fun buildQueries(
            filenameText: String,
            contentText: String,
            contentSearchEnabled: Boolean,
            filenameOptions: FilenameQuery,
            contentOptions: ContentQuery,
        ): Pair<FilenameQuery, ContentQuery> = Pair(
            FilenameQuery(
                pattern = filenameText,
                caseSensitive = filenameOptions.caseSensitive,
                wholeWord = filenameOptions.wholeWord,
                useRegex = filenameOptions.useRegex,
            ),
            ContentQuery(
                pattern = if (contentSearchEnabled) contentText else "",
                caseSensitive = contentOptions.caseSensitive,
                wholeWord = contentOptions.wholeWord,
                useRegex = contentOptions.useRegex,
            ),
        )
    }
}

/**
 * Phases of the access-error setup suggestion, used to edge-trigger the auto-rerun. Distinguishes
 * "requirements were reset/none exist" (NONE) from "a viable combo was actually completed"
 * (SATISFIED) — only NEEDS_SETUP -> SATISFIED may rerun; a new search's reset goes to NONE.
 */
private enum class AccessSetupPhase {
    NONE, NEEDS_SETUP, SATISFIED
}

private fun PathRequirements.toAccessSetupPhase(): AccessSetupPhase = when {
    !hasSetupOptions -> AccessSetupPhase.NONE
    needsSetup -> AccessSetupPhase.NEEDS_SETUP
    else -> AccessSetupPhase.SATISFIED
}
