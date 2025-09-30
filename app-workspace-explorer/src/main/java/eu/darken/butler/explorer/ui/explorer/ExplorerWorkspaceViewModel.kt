package eu.darken.butler.explorer.ui.explorer

import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerNavigation.Target.*
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.PatternMatcher
import eu.darken.butler.explorer.core.FileIntentHelper
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.locationId
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.sorting.ExplorerItemSorter
import eu.darken.butler.explorer.ui.explorer.actions.DefaultActionProvider
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemResult
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemType
import eu.darken.butler.explorer.ui.explorer.dialogs.DeleteConfirmationResult
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogEvent
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.*
import eu.darken.butler.explorer.ui.explorer.dialogs.RenameResult
import eu.darken.butler.explorer.ui.explorer.dialogs.FilterOptionsResult
import eu.darken.butler.explorer.ui.explorer.dialogs.SortOptionsResult
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.isPro
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.get
import eu.darken.butler.workspace.core.permissions.PermissionState
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
    navController: NavigationController,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val actionProvider: DefaultActionProvider,
    private val clipboardRepo: ClipboardRepo,
    private val fileIntentHelper: FileIntentHelper,
    private val explorerSettings: ExplorerSettings,
    itemSorterFactory: ExplorerItemSorter.Factory,
    private val operationsManager: OperationsManager,
    private val systemClipboardHelper: SystemClipboardHelper,
    private val copyErrorTool: CopyErrorTool,
    private val upgradeRepo: UpgradeRepo,
) : ViewModel4(dispatchers, logTag("Explorer", "Workspace", id.shortTag, "Page"), navController) {

    private val selectedItemsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val viewModeFlow = MutableStateFlow(ViewMode.LIST)
    private val dialogStateFlow = MutableStateFlow<ExplorerDialogState>(None)
    private val issueStateFlow = MutableStateFlow<Issue?>(null)
    private val filterStateFlow = MutableStateFlow(FilterState())
    val issueState = issueStateFlow
    private var currentConflictOperationId: Operation.Id? = null
    private val showIssueSheetFlow = MutableSharedFlow<Unit>()
    val showIssueSheetEvent = showIssueSheetFlow.asSharedFlow()

    val dialogEvents = SingleEventFlow<ExplorerDialogEvent>()

    private val workspaceSource: Flow<ExplorerWorkspace?> =
        workspaceProvider.retrieve(id).map { it as ExplorerWorkspace? }
    private val itemSorter = itemSorterFactory.create(id)
    private val currentSortSettings = MutableStateFlow(explorerSettings.sortSettings.valueBlocking)
    private suspend fun getWorkspace() = workspaceSource.filterNotNull().first()

    private val workspaceState: Flow<ExplorerWorkspace.State> = workspaceSource.flatMapLatest { ws ->
        ws?.state ?: flowOf(ExplorerWorkspace.State())
    }

    init {
        // Handle dialog events
        dialogEvents
            .onEach { event ->
                handleDialogEvent(event)
            }
            .launchInViewModel()

        // Observe pending conflicts from workspace and update UI state
        workspaceSource
            .filterNotNull()
            .flatMapLatest { it.operations }
            .map { it.pendingConflicts }
            .distinctUntilChanged()
            .onEach { conflicts ->
                val firstConflictEntry = conflicts.entries.firstOrNull()
                if (firstConflictEntry != null) {
                    val (operationId, awaitingInputState) = firstConflictEntry
                    currentConflictOperationId = operationId
                    issueStateFlow.value = awaitingInputState
                } else {
                    currentConflictOperationId = null
                    issueStateFlow.value = null
                }
            }
            .launchInViewModel()
    }

    enum class ViewMode {
        LIST,
        GRID
    }

    data class State(
        internal val currentLocation: ExplorerLocation? = null,
        val locationId: String? = null,
        val breadcrumbs: List<ExplorerBreadcrumb> = emptyList(),
        val items: List<ExplorerItem>? = null,
        val error: Throwable? = null,
        val selectionState: ExplorerSelectionState = ExplorerSelectionState(),
        val viewMode: ViewMode = ViewMode.LIST,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val availableActions: List<ExplorerAction> = emptyList(),
        val dialogState: ExplorerDialogState = None,
        val permissionState: PermissionState = PermissionState(),
        val isPro: Boolean = false,
        val filterState: FilterState = FilterState(),
        val useRegexPatterns: Boolean = false,
    ) {
        val progress = currentLocation?.progress
        val info = currentLocation?.info
    }

    val state = combine(
        workspaceState,
        selectedItemsFlow,
        viewModeFlow,
        dialogStateFlow,
        currentSortSettings,
        upgradeRepo.upgradeInfo,
        filterStateFlow,
        explorerSettings.useRegexPatterns.flow,
    ) { wsState, selectedItems, viewMode, dialogState, sortSetting, upgradeInfo, filterState, useRegexPatterns ->
        val items = wsState.currentLocation?.items
            ?.let { itemSorter.sortItems(it, sortSetting) }
            ?.let { sortedItems -> applyFilters(sortedItems, filterState, useRegexPatterns) }

        val selectionState = ExplorerSelectionState(
            selectedItems = selectedItems,
            selectableItems = items
                ?.filter { it is ExplorerItem.Path }
                ?.map { it.id }
                ?.toSet()
                ?: emptySet(),
        )

        val availableActions = wsState.currentLocation?.let {
            actionProvider.getActions(
                location = it,
                selectionState = selectionState,
            ).map { action ->
                // Add badge to Filter action if filters are active
                if (action is ExplorerAction.Common.Filter) {
                    val hasActiveFilters = filterState.fileTypeFilter != FileTypeFilter.ALL
                            || filterState.includePattern.isNotBlank()
                            || filterState.excludePattern.isNotBlank()

                    if (hasActiveFilters) {
                        action.copy(badge = true)
                    } else {
                        action
                    }
                } else {
                    action
                }
            }
        } ?: emptyList()

        State(
            currentLocation = wsState.currentLocation,
            locationId = wsState.currentLocation?.locationId,
            breadcrumbs = wsState.currentBreadcrumbs ?: emptyList(),
            items = items,
            error = wsState.error,
            selectionState = selectionState,
            viewMode = viewMode,
            canGoBack = wsState.canGoBack,
            canGoForward = wsState.canGoForward,
            availableActions = availableActions,
            dialogState = dialogState,
            permissionState = wsState.currentLocation?.permissionState ?: PermissionState(),
            isPro = upgradeInfo.isUpgraded,
            filterState = filterState,
            useRegexPatterns = useRegexPatterns,
        )
    }.asStateFlow()

    private fun applyFilters(
        items: List<ExplorerItem>,
        filterState: FilterState,
        useRegexPatterns: Boolean,
    ): List<ExplorerItem> {
        return items.filter { item ->
            val itemName = when (item) {
                is ExplorerItem.Path -> item.path.name
                else -> return@filter true // Keep non-path items (like peek items)
            }

            // Apply exclude pattern first
            if (filterState.excludePattern.isNotBlank()) {
                val excludeRegex = PatternMatcher.toRegexPattern(filterState.excludePattern, useRegexPatterns)
                if (PatternMatcher.matches(itemName, excludeRegex)) {
                    return@filter false
                }
            }

            // Apply include pattern
            if (filterState.includePattern.isNotBlank()) {
                val includeRegex = PatternMatcher.toRegexPattern(filterState.includePattern, useRegexPatterns)
                if (!PatternMatcher.matches(itemName, includeRegex)) {
                    return@filter false
                }
            }

            // Apply file type filter
            when (filterState.fileTypeFilter) {
                FileTypeFilter.FILES_ONLY -> if (item is ExplorerItem.Directory) return@filter false
                FileTypeFilter.FOLDERS_ONLY -> if (item is ExplorerItem.File) return@filter false
                FileTypeFilter.ALL -> {} // No filtering needed
            }

            true
        }
    }

    val clipboard = clipboardRepo.state
        .map { repoState -> ClipboardState(entries = repoState.entries) }
        .asStateFlow()

    data class ClipboardState(
        val entries: List<ClipboardClip> = emptyList(),
    )

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
                        // Priority: Running > Waiting (was running, needs input) > Queued > Others
                        when (op.state) {
                            is OperationDisplay.State.Running -> 0
                            is OperationDisplay.State.Waiting -> 1  // Higher priority than queued
                            is OperationDisplay.State.Queued -> 2
                            is OperationDisplay.State.Failed -> 3
                            is OperationDisplay.State.Cancelled -> 4
                            is OperationDisplay.State.Completed -> 5
                        }
                    }.thenBy { it.startedAt } // Oldest first within each group
                )
            OperationsState(operations = ops)
        }
        .onStart { emit(OperationsState()) }
        .asStateFlow()

    fun navigate(item: ExplorerItem) = launch {
        log(tag) { "navigate($item)" }
        when (item) {
            is ExplorerItem.Path -> when (item) {
                is ExplorerItem.Directory -> {
                    getWorkspace().navigate(Directory(item.lookup.lookedUp))
                    clearSelection()
                }
                is ExplorerItem.File -> {
                    dialogStateFlow.value = FileOptions(item)
                }
                is ExplorerItem.Peek -> {
                    // NOOP
                }
            }
            is ExplorerItem.Shortcut -> {
                getWorkspace().navigate(item.target)
                clearSelection()
            }

        }
    }

    fun navigateToPathString(pathString: String) = launch {
        log(tag) { "navigateToPathString($pathString)" }
        val normalizedPath = pathString.trim()

        when {
            normalizedPath.isEmpty() -> {
                getWorkspace().navigate(Home)
            }
            normalizedPath.startsWith("/") -> {
                getWorkspace().navigate(Directory(LocalPath.build(normalizedPath)))
                clearSelection()
            }
            else -> {
                // Invalid path - could show error
                log(tag, WARN) { "Invalid path: $pathString" }
            }
        }
    }

    fun navigate(target: ExplorerNavigation) = launch {
        log(tag) { "navigate($target)" }
        getWorkspace().navigate(target)
        clearSelection()
    }

    fun toggleItemSelection(item: ExplorerItem) {
        log(tag) { "toggleItemSelection($item)" }
        if (item !is ExplorerItem.Path) {
            log(tag, WARN) { "toggleItemSelection($item) is not a path" }
            return
        }
        val path = item.path.path
        val currentSelection = selectedItemsFlow.value
        selectedItemsFlow.value = if (currentSelection.contains(path)) {
            currentSelection - path
        } else {
            currentSelection + path
        }
    }

    fun clearSelection() {
        selectedItemsFlow.value = emptySet()
    }

    fun executeAction(action: ExplorerAction) = launch {
        log(tag) { "executeAction(${action::class.simpleName})" }
        val stateSnap = state.first()
        if (stateSnap.items == null) return@launch
        when (action) {
            is ExplorerAction.Directory.Create -> {
                dialogEvents.emit(ExplorerDialogEvent.ShowCreateItem)
            }
            is ExplorerAction.Directory.Rename -> {
                val item = stateSnap.items.find { it.id == stateSnap.selectionState.selectedItems.single() }
                item as ExplorerItem.Lookup
                val event = ExplorerDialogEvent.ShowRename(
                    item = item.lookup.lookedUp,
                )
                dialogEvents.emit(event)
            }
            is ExplorerAction.Directory.Copy -> {
                log(tag) { "copySelectedItems(): ${selectedItemsFlow.value.size} items" }
                val selected = selectedItemsFlow.value
                if (selected.isEmpty()) return@launch
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.COPY,
                    origin = getWorkspace().id,
                    paths = stateSnap.items
                        .filter { it.id in selected }
                        .filterIsInstance<ExplorerItem.Lookup>()
                        .map { it.lookup.lookedUp },
                )
                clipboardRepo.add(clip)
                clearSelection()
            }
            is ExplorerAction.Directory.Cut -> {
                log(tag) { "cutSelectedItems(): ${selectedItemsFlow.value.size} items" }
                val selected = selectedItemsFlow.value
                if (selected.isEmpty()) return@launch
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.CUT,
                    origin = getWorkspace().id,
                    paths = stateSnap.items
                        .filter { it.id in selected }
                        .filterIsInstance<ExplorerItem.Lookup>()
                        .map { it.lookup.lookedUp },
                )
                clipboardRepo.add(clip)
                clearSelection()
            }
            is ExplorerAction.Directory.Delete -> {
                log(tag) { "deleteSelectedItems(): ${selectedItemsFlow.value.size} items" }
                val selectedPaths = selectedItemsFlow.value
                if (selectedPaths.isNotEmpty()) {
                    val currentLocation = stateSnap.currentLocation
                    if (currentLocation is ExplorerLocation.Directory) {
                        // Convert string paths to APath objects
                        val pathsToDelete = selectedPaths.mapNotNull { pathString ->
                            try {
                                LocalPath.build(pathString)
                            } catch (e: Exception) {
                                log(tag, WARN) { "Failed to parse path: $pathString" }
                                null
                            }
                        }.toSet()

                        if (pathsToDelete.isNotEmpty()) {
                            dialogEvents.emit(ExplorerDialogEvent.ShowDeleteConfirmation(pathsToDelete))
                        }
                    }
                }
            }
            is ExplorerAction.Directory.Share -> {
                log(tag) { "shareSelectedItems(): ${selectedItemsFlow.value.size} items" }
                // TODO: Implement share via Android share sheet
            }
            is ExplorerAction.Directory.SelectAll -> {
                selectedItemsFlow.value = stateSnap.selectionState.selectableItems
            }
            is ExplorerAction.Directory.DeselectAll -> {
                selectedItemsFlow.value = emptySet()
            }
            is ExplorerAction.Common.Sort -> {
                dialogStateFlow.value = EditSortOptions(
                    currentSortSettings = currentSortSettings.value
                )
            }
            is ExplorerAction.Common.Filter -> {
                val filterState = filterStateFlow.value
                dialogStateFlow.value = FilterOptions(
                    includePattern = filterState.includePattern,
                    excludePattern = filterState.excludePattern,
                    fileTypeFilter = filterState.fileTypeFilter,
                    useRegexPatterns = explorerSettings.useRegexPatterns.valueBlocking,
                )
            }
            is ExplorerAction.Common.ToggleView -> {
                viewModeFlow.value = when (viewModeFlow.value) {
                    ViewMode.LIST -> ViewMode.GRID
                    ViewMode.GRID -> ViewMode.LIST
                }
            }
            is ExplorerAction.Common.Refresh -> {
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            }
        }
    }

    // File action handlers
    fun openFileInEditor(item: ExplorerItem.File) = launch {
        log(tag) { "openFileInEditor(${item.lookup.name})" }
        dismissDialog()

        // Create editor workspace arguments via reflection to avoid direct dependency
        try {
            val editorArgsClass = Class.forName("eu.darken.butler.editor.core.EditorWorkspace\$Arguments")
            val constructor = editorArgsClass.getConstructor(
                eu.darken.butler.common.files.APath::class.java,
                Long::class.java,
                Long::class.java,
                Boolean::class.java,
                Int::class.java,
                String::class.java
            )
            val editorArguments = constructor.newInstance(
                item.lookup.lookedUp, // filePath
                null, // chunkSize - use default
                null, // memoryLimit - use default
                false, // isReadOnly
                null, // goToLine
                null // searchQuery
            ) as Workspace.Arguments

            val action = WorkspaceAction.Create(
                type = Workspace.Type.EDITOR,
                arguments = editorArguments
            )

            workspaceRemote.execute(action)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create editor workspace: ${e.message}" }
            // TODO: Show error message to user
        }
    }

    fun openFileWith(item: ExplorerItem.File) = launch {
        log(tag) { "openFileWith(${item.lookup.name})" }
        dismissDialog()

        val intent = fileIntentHelper.openFileWith(item)
        if (intent != null && fileIntentHelper.canHandleIntent(intent)) {
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to open file with external app: ${e.message}" }
                // TODO: Show error message to user
            }
        } else {
            log(tag, WARN) { "No app found to open file: ${item.lookup.name}" }
            // TODO: Show "no app found" message to user
        }
    }

    fun shareFile(item: ExplorerItem.File) = launch {
        log(tag) { "shareFile(${item.lookup.name})" }
        dismissDialog()

        val intent = fileIntentHelper.shareFile(item)
        if (intent != null) {
            try {
                val chooserIntent = Intent.createChooser(intent, "Share ${item.lookup.name}")
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooserIntent)
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to share file: ${e.message}" }
                // TODO: Show error message to user
            }
        } else {
            log(tag, WARN) { "Failed to create share intent for: ${item.lookup.name}" }
            // TODO: Show error message to user
        }
    }

    fun copyFile(item: ExplorerItem.File) = launch {
        log(tag) { "copyFile(${item.lookup.name})" }
        dismissDialog()

        val clip = ClipboardClip.Paths(
            mode = ClipboardClip.Paths.Mode.COPY,
            origin = getWorkspace().id,
            paths = listOf(item.lookup.lookedUp),
        )
        clipboardRepo.add(clip)
    }

    fun cutFile(item: ExplorerItem.File) = launch {
        log(tag) { "cutFile(${item.lookup.name})" }
        dismissDialog()

        val clip = ClipboardClip.Paths(
            mode = ClipboardClip.Paths.Mode.CUT,
            origin = getWorkspace().id,
            paths = listOf(item.lookup.lookedUp),
        )
        clipboardRepo.add(clip)
    }

    fun renameFile(item: ExplorerItem.File) = launch {
        log(tag) { "renameFile(${item.lookup.name})" }
        dismissDialog()

        val event = ExplorerDialogEvent.ShowRename(
            item = item.lookup.lookedUp,
        )
        dialogEvents.emit(event)
    }

    fun deleteFile(item: ExplorerItem.File) = launch {
        log(tag) { "deleteFile(${item.lookup.name})" }
        dismissDialog()

        dialogEvents.emit(
            ExplorerDialogEvent.ShowDeleteConfirmation(
                items = setOf(item.lookup.lookedUp)
            )
        )
    }

    fun showFileProperties(item: ExplorerItem.File) = launch {
        log(tag) { "showFileProperties(${item.lookup.name})" }
        dismissDialog()
        // TODO: Implement file properties dialog
    }

    private suspend fun handleDialogEvent(event: ExplorerDialogEvent) {
        log(tag) { "handleDialogEvent($event)" }
        when (event) {
            is ExplorerDialogEvent.ShowCreateItem -> {
                dialogStateFlow.value = CreateItem
            }
            is ExplorerDialogEvent.ShowDeleteConfirmation -> {
                dialogStateFlow.value = DeleteConfirmation(event.items)
            }
            is ExplorerDialogEvent.ShowRename -> {
                dialogStateFlow.value = Rename(event.item)
            }
            is ExplorerDialogEvent.ShowFilterOptions -> {
                val filterState = filterStateFlow.value
                dialogStateFlow.value = FilterOptions(
                    includePattern = filterState.includePattern,
                    excludePattern = filterState.excludePattern,
                    fileTypeFilter = filterState.fileTypeFilter,
                    useRegexPatterns = explorerSettings.useRegexPatterns.valueBlocking,
                )
            }
            is ExplorerDialogEvent.Dismiss -> {
                dialogStateFlow.value = None
            }
        }
    }

    fun dismissDialog() {
        dialogStateFlow.value = None
    }

    fun onCreateItem(result: CreateItemResult) = launch {
        log(tag) { "onCreateItem($result)" }
        dialogStateFlow.value = None

        val currentLocation = state.first().currentLocation
        if (currentLocation is ExplorerLocation.Directory) {
            val operation = when (result.type) {
                CreateItemType.FOLDER -> ExplorerCommand.Create(
                    parentPath = currentLocation.path,
                    name = result.name,
                    type = ExplorerCommand.Create.Type.DIRECTORY,
                )
                CreateItemType.FILE -> ExplorerCommand.Create(
                    parentPath = currentLocation.path,
                    name = result.name,
                    type = ExplorerCommand.Create.Type.FILE,
                )
            }
            getWorkspace().execute(operation)
        }
    }

    fun onDeleteConfirmed(result: DeleteConfirmationResult) = launch {
        log(tag) { "onDeleteConfirmed($result)" }
        dialogStateFlow.value = None

        if (result.items.isNotEmpty()) {
            getWorkspace().execute(
                ExplorerCommand.Delete(
                    targets = result.items,
                )
            )
            clearSelection()
        }
    }

    fun onRename(result: RenameResult) = launch {
        log(tag) { "onRename($result)" }
        dialogStateFlow.value = None

        val currentLocation = state.first().currentLocation as ExplorerLocation.Directory
        getWorkspace().execute(
            ExplorerCommand.Move(
                sources = setOf(result.item),
                destination = currentLocation.path.child(result.newName),
            )
        )
    }

    fun onSortOptions(result: SortOptionsResult) = launch {
        log(tag) { "onSortOptions($result)" }
        dialogStateFlow.value = None
        explorerSettings.sortSettings.value(result.sortSettings)
        currentSortSettings.value = result.sortSettings
    }

    fun onFilterOptions(result: FilterOptionsResult) = launch {
        log(tag) { "onFilterOptions($result)" }
        dialogStateFlow.value = None
        filterStateFlow.value = FilterState(
            includePattern = result.includePattern,
            excludePattern = result.excludePattern,
            fileTypeFilter = result.fileTypeFilter,
        )
    }

    fun pasteClipboard(clip: ClipboardClip) = launch {
        log(tag) { "pasteClipboard($clip)" }
        dismissDialog()
        when (clip) {
            is ClipboardClip.Paths -> {
                val currentLocation = state.first().currentLocation
                if (currentLocation is ExplorerLocation.Directory) {
                    val operation = when (clip.mode) {
                        ClipboardClip.Paths.Mode.COPY -> ExplorerCommand.Copy(
                            sources = clip.paths.toSet(),
                            destination = currentLocation.path,
                        )
                        ClipboardClip.Paths.Mode.CUT -> ExplorerCommand.Move(
                            sources = clip.paths.toSet(),
                            destination = currentLocation.path,
                        )
                    }
                    getWorkspace().execute(operation)

                    if (clip.mode == ClipboardClip.Paths.Mode.CUT) {
                        clipboardRepo.remove(clip.id)
                    }
                }
            }
        }
    }

    fun removeClipboardEntry(clip: ClipboardClip) = launch {
        log(tag) { "removeClipboardEntry($clip)" }
        dismissDialog()
        clipboardRepo.remove(clip.id)
    }

    fun clearAllClipboard() = launch {
        log(tag) { "clearAllClipboard()" }
        clipboardRepo.clear()
    }

    fun resolveConflict(resolution: PathActionIssue.Resolution) = launch {
        log(tag) { "resolveConflict(): $resolution" }

        val operationId = currentConflictOperationId
        if (operationId != null) {
            // Forward resolution to workspace
            getWorkspace().resolveConflict(operationId, resolution)
        } else {
            log(tag, WARN) { "Cannot resolve conflict: no current operation ID" }
        }

        // Clear conflict UI state (it will be updated by workspace state observer if needed)
        issueStateFlow.value = null
        currentConflictOperationId = null
    }

    fun showConflictSheet() = launch {
        log(tag) { "showConflictSheet(): Requesting to show conflict sheet" }
        if (issueStateFlow.value != null) {
            showIssueSheetFlow.emit(Unit)
        } else {
            log(tag, WARN) { "Cannot show conflict sheet: no current conflict" }
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

    fun copyError(id: Operation.Id) = launch {
        log(tag) { "copyError($id)" }
        val operation = operationsManager.get(id)
        if (operation == null) {
            log(tag, ERROR) { "Operation with id $id not found" }
            return@launch
        }
        copyErrorTool.formatError(operation)?.let {
            systemClipboardHelper.copyToClipboard(it)
        }
    }

    fun cancelOperation(id: Operation.Id) = launch {
        log(tag) { "cancelOperation($id)" }
        operationsManager.cancel(id)
    }

    fun dismissOperation(id: Operation.Id) = launch {
        log(tag) { "dismissOperation($id)" }
        operationsManager.remove(id)
    }

    fun clearCompletedOperations() = launch {
        log(tag) { "clearCompletedOperations()" }
        operationsManager.clearCompleted()
    }

    fun showClipboardInfo(clip: ClipboardClip) {
        log(tag) { "showClipboardInfo($clip)" }
        dialogStateFlow.value = ClipboardInfo(clip)
    }

    fun navigateToClipboardSource(clip: ClipboardClip) = launch {
        log(tag) { "navigateToClipboardSource($clip)" }
        dismissDialog()

        when (clip) {
            is ClipboardClip.Paths -> {
                if (clip.paths.isNotEmpty()) {
                    val firstPath = clip.paths.first()
                    val parentPath = firstPath.parent
                    if (parentPath != null) {
                        getWorkspace().navigate(Directory(parentPath))
                    }
                }
            }
        }
    }

    fun copyPathToSystemClipboard(path: String) = launch {
        log(tag) { "copyPathToSystemClipboard($path)" }
        systemClipboardHelper.copyToClipboard(path)
    }

    fun onButlerIconClick() = launch {
        log(tag) { "onButlerIconClick()" }
        if (upgradeRepo.isPro()) {
            log(tag) { "User has Pro - opening settings" }
            navTo(Nav.Main.settings())
        } else {
            log(tag) { "User doesn't have Pro - opening upgrade screen" }
            navTo(Nav.Main.upgrade())
        }
    }

    fun copyNavigationError() = launch {
        log(tag) { "copyNavigationError()" }
        workspaceState.first().error?.let { throwable ->
            systemClipboardHelper.copyToClipboard(formatNavigationError(throwable))
        }
    }

    fun retryNavigation() = launch {
        log(tag) { "retryNavigation()" }
        getWorkspace().navigate(ExplorerNavigation.Refresh)
    }

    fun dismissNavigationError() = launch {
        log(tag) { "dismissNavigationError()" }
        // Simply triggering any navigation request will clear the error state
        // We use Cancel as it's the least intrusive option
        getWorkspace().navigate(ExplorerNavigation.Back)
    }

    private fun formatNavigationError(error: Throwable): String {
        return """
            # Navigation Error
            * `${Build.FINGERPRINT}`
            * `${BuildConfigWrap.VERSION_DESCRIPTION}`
            * WorkspaceID: `${id.longTag}`

            ## Error
            ${error.message ?: error.javaClass.simpleName}

            ```java
            ${error.stackTraceToString()}
            ```
        """.trimIndent()
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}