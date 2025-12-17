package eu.darken.butler.explorer.ui.explorer

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.arguments.EditorArguments
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory
import eu.darken.butler.explorer.core.ExplorerNavigation.Target.Trash
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.FileIntentHelper
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.core.PatternMatcher
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerItem.Path.Companion.toPathItemId
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.TrashItemReference
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.explorer.core.sorting.ExplorerItemSorter
import eu.darken.butler.explorer.ui.explorer.actions.DefaultActionProvider
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemResult
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemType
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogEvent
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.ClipboardInfo
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.CreateItem
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.DeleteConfirmation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.EditSortOptions
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.EmptyTrashConfirmation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.FileOptions
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.FilterOptions
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.ItemInfo
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.LocationStorageName
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.None
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.RemoveLocationConfirmation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.Rename
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.TrashItemOptions
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.TrashNestedItemOptions
import eu.darken.butler.explorer.ui.explorer.dialogs.FilterOptionsResult
import eu.darken.butler.explorer.ui.explorer.dialogs.RenameResult
import eu.darken.butler.explorer.ui.explorer.dialogs.SortOptionsResult
import eu.darken.butler.explorer.ui.picker.ExplorerPickerHelper
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.permissions.core.SAFPickerGrant
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.isPro
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.ShareIntentUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.cancelResult
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.get
import eu.darken.butler.workspace.core.returnResult
import eu.darken.butler.workspace.ui.operations.CopyErrorTool
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val actionProvider: DefaultActionProvider,
    private val clipboardRepo: ClipboardRepo,
    private val openInNewTabsUseCase: OpenInNewTabsUseCase,
    private val shareIntentUseCase: ShareIntentUseCase,
    private val fileIntentHelper: FileIntentHelper,
    private val explorerSettings: ExplorerSettings,
    itemSorterFactory: ExplorerItemSorter.Factory,
    private val operationsManager: OperationsManager,
    private val systemClipboardHelper: SystemClipboardHelper,
    private val copyErrorTool: CopyErrorTool,
    private val upgradeRepo: UpgradeRepo,
    private val filenameValidator: FilenameValidator,
    private val gatewaySwitch: GatewaySwitch,
    internal val safLocationManager: SAFLocationManager,
    private val trashManager: TrashManager,
    private val trashRepo: TrashRepo,
    private val itemInfoCalculator: ItemInfoCalculator,
    private val pickerHelper: ExplorerPickerHelper,
    private val errorReportTool: ErrorReportTool,
) : ViewModel4(dispatchers, logTag("Explorer", "Workspace", id.shortTag, "Page")) {

    private val selectedItemsFlow = MutableStateFlow<Set<ExplorerItem>>(emptySet())
    private val viewStyleFlow = MutableStateFlow<ExplorerViewStyle>(explorerSettings.defaultViewStyle.valueBlocking)
    private val dialogStateFlow = MutableStateFlow<ExplorerDialogState>(None)
    private val issueStateFlow = MutableStateFlow<Issue?>(null)
    private val filterStateFlow = MutableStateFlow(FilterState())
    val issueState = issueStateFlow
    private var currentConflictOperationId: Operation.Id? = null
    private val showIssueSheetFlow = MutableSharedFlow<Unit>()
    val showIssueSheetEvent = showIssueSheetFlow.asSharedFlow()
    private val showAddStorageSheetFlow = MutableStateFlow(false)
    val showAddStorageSheet = showAddStorageSheetFlow

    val dialogEvents = SingleEventFlow<ExplorerDialogEvent>()

    val safPickerEvents = SingleEventFlow<Intent>()

    // Reveal and highlight functionality
    data class RevealRequest(
        val path: APath<*>,
        val highlight: Boolean = true,
        val highlightDurationMs: Long = 2000L,
    )

    val revealRequests = SingleEventFlow<RevealRequest>()
    private val highlightedItemIds = MutableStateFlow<Set<String>>(emptySet())

    private val _pendingSAFPickerGrant = MutableStateFlow<SAFPickerGrant?>(null)
    val pendingSAFPickerGrant: Flow<SAFPickerGrant?> = _pendingSAFPickerGrant

    // Scroll position tracking: Map<locationId, Pair<firstVisibleItemIndex, scrollOffset>>
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    private val workspaceSource: Flow<ExplorerWorkspace?> =
        workspaceProvider.retrieve(id).map { it as ExplorerWorkspace? }
    private val itemSorter = itemSorterFactory.create(id)
    private val currentSortSettings = MutableStateFlow(explorerSettings.sortSettings.valueBlocking)
    private suspend fun getWorkspace() = workspaceSource.filterNotNull().first()

    private val workspaceState: Flow<ExplorerWorkspace.State> = workspaceSource.flatMapLatest { ws ->
        ws?.state ?: flowOf(ExplorerWorkspace.State())
    }

    // Picker configuration (null for non-picker workspaces)
    private val pickerConfigFlow: Flow<PickerConfig?> = workspaceSource.map { it?.pickerConfig }

    // SaveAs filename (only used in SaveAs picker mode)
    private val saveAsFilenameFlow: Flow<String> = workspaceSource.flatMapLatest { ws ->
        ws?.saveAsFilename ?: flowOf("")
    }

    init {
        // Handle dialog events
        dialogEvents
            .onEach { event ->
                handleDialogEvent(event)
            }
            .launchInViewModel()

        // Clear highlights when navigating to a different location
        workspaceState
            .map { it.currentLocation?.locationId }
            .distinctUntilChanged()
            .onEach { clearHighlights() }
            .launchInViewModel()
    }

    data class State(
        internal val currentLocation: ExplorerLocation? = null,
        val locationId: String? = null,
        val breadcrumbs: List<ExplorerBreadcrumb> = emptyList(),
        val items: List<ExplorerItem>? = null,
        val error: Throwable? = null,
        val selectionState: ExplorerSelectionState = ExplorerSelectionState(),
        val viewStyle: ExplorerViewStyle = ExplorerViewStyle.default(),
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val availableActions: List<ExplorerAction> = emptyList(),
        val dialogState: ExplorerDialogState = None,
        val setupRequirements: PathRequirements = PathRequirements(),
        val isPro: Boolean = false,
        val filterState: FilterState = FilterState(),
        val useRegexPatterns: Boolean = false,
        val useBackButtonForNavigation: Boolean = false,
        val pickerConfig: PickerConfig? = null,
        val sortSettings: SortSettings = SortSettings(),
        val trashEnabled: Boolean = false,
        val saveAsFilename: String = "",
        val disabledItems: Set<ExplorerItem> = emptySet(),
        val canConfirmSelection: Boolean = true,
        val highlightedItemIds: Set<String> = emptySet(),
    ) {
        val progress = currentLocation?.progress
        val info = currentLocation?.info

        fun shouldShowSelection(item: ExplorerItem): Boolean {
            // Must be selectable
            if (item !in selectionState.selectableItems) return false

            // Show in multi-select picker modes (even before any items selected)
            if (pickerConfig?.selection?.isMultiSelect == true) return true

            // Show when in selection mode (normal browsing)
            return selectionState.selectedItems.isNotEmpty()
        }
    }

    // Optimization: Process items separately - only re-sort when items/sort/filter actually change
    private val processedItemsFlow: Flow<List<ExplorerItem>?> = combine(
        workspaceState.map { it.currentLocation?.items }.distinctUntilChanged(),
        currentSortSettings,
        filterStateFlow,
        explorerSettings.useRegexPatterns.flow,
    ) { items, sortSetting, filterState, useRegexPatterns ->
        items
            ?.let { applyFilters(it, filterState, useRegexPatterns) }
            ?.let { itemSorter.sortItems(it, sortSetting) }
    }

    // Optimization: Selection state only updates when items or selection changes
    private val derivedSelectionStateFlow: Flow<ExplorerSelectionState> = combine(
        processedItemsFlow,
        selectedItemsFlow,
        pickerConfigFlow,
    ) { items, selectedItems, pickerConfig ->
        ExplorerSelectionState(
            selectedItems = selectedItems,
            selectableItems = items?.let { pickerHelper.filterSelectableItems(it, pickerConfig) } ?: emptySet(),
        )
    }

    val state = combine(
        workspaceState,
        processedItemsFlow,
        derivedSelectionStateFlow,
        viewStyleFlow,
        dialogStateFlow,
        currentSortSettings,
        upgradeRepo.upgradeInfo,
        filterStateFlow,
        explorerSettings.useRegexPatterns.flow,
        explorerSettings.useBackButtonForNavigation.flow,
        pickerConfigFlow,
        trashManager.isEnabled,
        saveAsFilenameFlow,
        highlightedItemIds,
    ) { wsState, items, selectionState, viewStyle, dialogState, sortSetting, upgradeInfo, filterState, useRegexPatterns, useBackButtonForNavigation, pickerConfig, recycleBinEnabled, saveAsFilename, highlightedItemIds ->
        // Items already filtered and sorted by processedItemsFlow
        // Selection state already computed by derivedSelectionStateFlow

        val disabledItems = items?.let { pickerHelper.computeDisabledItems(it, pickerConfig) } ?: emptySet()

        // Compute whether picker confirm is allowed
        val canConfirmSelection = pickerHelper.canConfirmSelection(
            config = pickerConfig,
            currentLocation = wsState.currentLocation,
            selectedItems = selectionState.selectedItems,
            saveAsFilename = saveAsFilename,
        )

        val rawActions = wsState.currentLocation?.let {
            actionProvider.getActions(
                location = it,
                selectionState = selectionState,
                viewStyle = viewStyle,
                trashEnabled = recycleBinEnabled,
            )
        } ?: emptyList()

        val availableActions = pickerHelper.filterActionsForPicker(rawActions, pickerConfig)
            .map { action ->
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

        State(
            currentLocation = wsState.currentLocation,
            locationId = wsState.currentLocation?.locationId,
            breadcrumbs = wsState.currentBreadcrumbs ?: emptyList(),
            items = items,
            error = wsState.error,
            selectionState = selectionState,
            viewStyle = viewStyle,
            canGoBack = wsState.canGoBack,
            canGoForward = wsState.canGoForward,
            availableActions = availableActions,
            dialogState = dialogState,
            setupRequirements = wsState.currentLocation?.setupRequirements ?: PathRequirements(),
            isPro = upgradeInfo.isUpgraded,
            filterState = filterState,
            useRegexPatterns = useRegexPatterns,
            useBackButtonForNavigation = useBackButtonForNavigation,
            pickerConfig = pickerConfig,
            sortSettings = sortSetting,
            trashEnabled = recycleBinEnabled,
            saveAsFilename = saveAsFilename,
            disabledItems = disabledItems,
            canConfirmSelection = canConfirmSelection,
            highlightedItemIds = highlightedItemIds,
        )
    }
        .distinctUntilChanged()
        .asStateFlow()

    private fun applyFilters(
        items: List<ExplorerItem>,
        filterState: FilterState,
        useRegexPatterns: Boolean,
    ): List<ExplorerItem> {
        return items.filter { item ->
            val itemName = when (item) {
                is ExplorerItem.Path -> item.path.name
                is ExplorerItem.Trash.Root -> item.originalLookup.name
                is ExplorerItem.Trash.Nested -> item.lookup.name
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
                FileTypeFilter.FILES_ONLY -> {
                    if (item is ExplorerItem.Directory) return@filter false
                    if (item is ExplorerItem.Trash.Root && item.originalLookup.fileType == FileType.DIRECTORY) return@filter false
                    if (item is ExplorerItem.Trash.Nested && item.isDirectory) return@filter false
                }
                FileTypeFilter.FOLDERS_ONLY -> {
                    if (item is ExplorerItem.File) return@filter false
                    if (item is ExplorerItem.Trash.Root && item.originalLookup.fileType == FileType.FILE) return@filter false
                    if (item is ExplorerItem.Trash.Nested && item.isFile) return@filter false
                }
                FileTypeFilter.ALL -> {} // No filtering needed
            }

            true
        }
    }

    val clipboard = clipboardRepo.state
        .map { repoState -> ClipboardState(entries = repoState.entries) }
        .distinctUntilChanged()
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
                    }.thenByDescending { it.startedAt } // Newest first within each group
                )
            OperationsState(operations = ops)
        }
        .onStart { emit(OperationsState()) }
        .distinctUntilChanged()
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
                    // Special handling for symlinks: check if target is directory
                    if (item is ExplorerItem.SymbolicLink && !item.isBroken) {
                        val target = item.lookup.target
                        if (target != null) {
                            // Perform lookup to determine if target is a directory or file
                            val targetLookup = gatewaySwitch.lookup(
                                target,
                                LookupOptions(continueOnError = false)
                            )

                            if (targetLookup.isDirectory) {
                                log(tag, INFO) { "Following symlink to directory: ${item.targetPath}" }
                                getWorkspace().navigate(Directory(target))
                                clearSelection()
                                return@launch
                            } else {
                                log(tag, INFO) { "Symlink points to file: ${item.targetPath}" }
                                // Fall through to show file options dialog
                            }
                        }
                    }

                    val workspace = getWorkspace()
                    val config = workspace.pickerConfig

                    // FileSingle mode: instant selection on file tap
                    if (config?.selection?.instantFileSelection == true) {
                        log(tag, INFO) { "FileSingle instant selection: ${item.lookup.name}" }
                        workspaceRemote.returnResult(
                            WorkspaceEvent.PickerResult(
                                workspaceId = id,
                                callerWorkspaceId = config.callerWorkspaceId,
                                selectedPaths = listOf(item.lookup.lookedUp),
                            )
                        )
                    } else {
                        // Normal mode or other picker modes: show file options dialog
                        dialogStateFlow.value = FileOptions(item)
                    }
                }
                is ExplorerItem.Peek -> {
                    // NOOP
                }
            }
            is ExplorerItem.Shortcut -> {
                getWorkspace().navigate(item.target)
                clearSelection()
            }
            is ExplorerItem.Storage -> {
                getWorkspace().navigate(item.target)
                clearSelection()
            }
            is ExplorerItem.Trash.Root -> {
                if (selectedItemsFlow.value.isNotEmpty()) {
                    toggleItemSelection(item)
                } else if (item.trashLookup?.fileType == FileType.DIRECTORY && item.isAvailable) {
                    // Navigate into trashed folder
                    val ref = TrashItemReference.from(item)
                    getWorkspace().navigate(Trash.Nested(ref, ""))
                    clearSelection()
                } else {
                    dialogStateFlow.value = TrashItemOptions(item)
                }
            }
            is ExplorerItem.Trash.Nested -> {
                if (selectedItemsFlow.value.isNotEmpty()) {
                    toggleItemSelection(item)
                } else if (item.isDirectory) {
                    // Navigate deeper into nested trash
                    getWorkspace().navigate(Trash.Nested(item.parentRef, item.relativePath))
                    clearSelection()
                } else {
                    // Show options for nested files
                    dialogStateFlow.value = TrashNestedItemOptions(item)
                }
            }
        }
    }

    fun navigateToPath(path: APath<*>) = launch {
        log(tag) { "navigateToPath($path)" }
        getWorkspace().navigate(Directory(path))
        clearSelection()
    }

    fun navigate(target: ExplorerNavigation) = launch {
        log(tag) { "navigate($target)" }
        getWorkspace().navigate(target)
        clearSelection()
    }

    fun saveScrollPosition(locationId: String, firstVisibleItemIndex: Int, scrollOffset: Int) {
        scrollPositions[locationId] = firstVisibleItemIndex to scrollOffset
        log(tag) { "saveScrollPosition: locationId=$locationId, index=$firstVisibleItemIndex, offset=$scrollOffset" }
    }

    fun getScrollPosition(locationId: String): Pair<Int, Int>? {
        val position = scrollPositions[locationId]
        log(tag) { "getScrollPosition: locationId=$locationId -> $position" }
        return position
    }

    fun toggleItemSelection(item: ExplorerItem) {
        if (!item.isSelectable()) {
            log(tag, WARN) { "toggleItemSelection($item) is not selectable" }
            return
        }
        val pickerConfig = runBlocking { workspaceSource.first()?.pickerConfig }
        val currentSelection = selectedItemsFlow.value

        // In DirectorySingle mode with Storage items, enforce single selection (radio button behavior)
        val newSelection =
            if (pickerConfig?.selection is PickerConfig.Selection.DirectorySingle && item is ExplorerItem.Storage) {
                if (currentSelection.contains(item)) {
                    emptySet() // Deselect if clicking the same item
                } else {
                    setOf(item) // Replace selection with new item
                }
            } else {
                // Normal toggle behavior for multi-select modes
                if (currentSelection.contains(item)) {
                    currentSelection - item
                } else {
                    currentSelection + item
                }
            }
        selectedItemsFlow.value = newSelection
    }

    fun onItemClick(item: ExplorerItem) = launch {
        log(tag) { "onItemClick($item)" }
        val workspace = getWorkspace()
        val pickerConfig = workspace.pickerConfig

        when {
            // FileMulti mode: tap file to toggle selection
            pickerConfig?.selection is PickerConfig.Selection.FileMulti && item is ExplorerItem.File -> {
                toggleItemSelection(item)
            }
            // MixedMulti mode: tap file to toggle selection, tap folder to navigate
            pickerConfig?.selection is PickerConfig.Selection.MixedMulti && item is ExplorerItem.File -> {
                toggleItemSelection(item)
            }
            // Selection mode active: toggle selection
            selectedItemsFlow.value.isNotEmpty() -> {
                toggleItemSelection(item)
            }
            // Normal mode: navigate
            else -> {
                navigate(item)
            }
        }
    }

    fun onItemLongClick(item: ExplorerItem) {
        log(tag) { "onItemLongClick($item)" }
        val pickerConfig = runBlocking { workspaceSource.first()?.pickerConfig }

        // Enable long-press selection in:
        // - Normal mode (no picker)
        // - Multi-select picker modes
        // - DirectorySingle mode with Storage items (allows selecting storage volumes at Device level)
        val allowLongPress = pickerConfig == null
            || pickerConfig.selection.isMultiSelect
            || (pickerConfig.selection is PickerConfig.Selection.DirectorySingle && item is ExplorerItem.Storage)

        if (allowLongPress) {
            toggleItemSelection(item)
        }
    }

    fun clearSelection() {
        selectedItemsFlow.value = emptySet()
    }

    fun selectAll() = launch {
        val stateSnap = state.first()
        selectedItemsFlow.value = stateSnap.selectionState.selectableItems
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
                val item = stateSnap.selectionState.selectedItems.single() as ExplorerItem.Lookup
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
                    paths = selected
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
                    paths = selected
                        .filterIsInstance<ExplorerItem.Lookup>()
                        .map { it.lookup.lookedUp },
                )
                clipboardRepo.add(clip)
                clearSelection()
            }
            is ExplorerAction.Directory.Delete -> {
                log(tag) { "deleteSelectedItems(): ${selectedItemsFlow.value.size} items" }
                val selectedItems = selectedItemsFlow.value
                if (selectedItems.isNotEmpty()) {
                    val currentLocation = stateSnap.currentLocation
                    if (currentLocation is ExplorerLocation.Directory) {
                        val pathsToDelete = selectedItems
                            .filterIsInstance<ExplorerItem.Lookup>()
                            .map { it.lookup.lookedUp }
                            .toSet()

                        if (pathsToDelete.isNotEmpty()) {
                            dialogEvents.emit(ExplorerDialogEvent.ShowDeleteConfirmation(pathsToDelete))
                        }
                    }
                }
            }
            is ExplorerAction.Directory.Share -> {
                log(tag) { "shareSelectedItems(): ${selectedItemsFlow.value.size} items" }

                val selectedFiles = selectedItemsFlow.value.filterIsInstance<ExplorerItem.File>()
                if (selectedFiles.isEmpty()) {
                    log(tag, WARN) { "No files selected for sharing (directories cannot be shared)" }
                    return@launch
                }

                val shareItems = selectedFiles.map { file ->
                    object : ShareIntentUseCase.Item {
                        override val path = file.lookup.lookedUp
                        override val mimeType = file.mimeType.rawType
                        override val displayName = file.lookup.name
                    }
                }

                val chooserTitle = if (selectedFiles.size == 1) {
                    context.getString(
                        eu.darken.butler.common.R.string.general_share_single_title,
                        selectedFiles.first().lookup.name
                    )
                } else {
                    context.resources.getQuantityString(
                        eu.darken.butler.common.R.plurals.general_share_multiple_title,
                        selectedFiles.size,
                        selectedFiles.size
                    )
                }

                val success = shareIntentUseCase.shareWithChooser(shareItems, chooserTitle)
                if (!success) {
                    errorEvents.emit(Exception("Failed to share ${selectedFiles.size} files"))
                }
            }
            is ExplorerAction.Directory.SelectAll -> {
                selectedItemsFlow.value = stateSnap.selectionState.selectableItems
            }
            is ExplorerAction.Directory.DeselectAll -> {
                selectedItemsFlow.value = emptySet()
            }
            is ExplorerAction.Directory.OpenInNewTabs -> {
                log(tag) { "openInNewTabs(): ${selectedItemsFlow.value.size} items" }
                val selected = selectedItemsFlow.value.filterIsInstance<ExplorerItem.Lookup>()
                if (selected.isEmpty()) return@launch

                // Convert Explorer items to use case items
                val items = selected.map { item ->
                    if (item.lookup.isDirectory) {
                        OpenInNewTabsUseCase.Item.Directory(item.lookup.lookedUp)
                    } else {
                        val isText = when (item) {
                            is ExplorerItem.File -> TextFileDetector.isTextFile(item.mimeType)
                            else -> TextFileDetector.isTextFile(item.lookup.lookedUp)
                        }
                        OpenInNewTabsUseCase.Item.File(item.lookup.lookedUp, isText)
                    }
                }

                val request = OpenInNewTabsUseCase.Request(
                    items = items,
                    sourceWorkspaceId = id,
                )

                val analysis = openInNewTabsUseCase.analyze(request)

                if (!analysis.hasItemsToOpen) {
                    // All items were skipped
                    log(tag, WARN) { "All items skipped (no openable items)" }
                    return@launch
                }

                // Always emit event - WorkspacesViewModel handles confirmation
                executeOpenInNewTabs(analysis)
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
            is ExplorerAction.Common.UpdateViewStyle -> {
                viewStyleFlow.value = action.viewStyle
                launch {
                    explorerSettings.defaultViewStyle.value(action.viewStyle)
                }
            }
            is ExplorerAction.Common.Refresh -> {
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            }
            is ExplorerAction.Common.Info -> {
                log(tag) { "showInfo(): ${selectedItemsFlow.value.size} items selected" }

                // Only show info when items are selected
                if (selectedItemsFlow.value.isNotEmpty()) {
                    val selectedItems = selectedItemsFlow.value.toList()

                    val infoContext = itemInfoCalculator.calculateInfo(selectedItems, stateSnap.items)
                    infoContext?.let { context ->
                        dialogStateFlow.value = ItemInfo(context)
                    }
                }
            }
            is ExplorerAction.Device.AddLocation -> {
                showAddStorageSheet()
            }
            is ExplorerAction.Device.RemoveLocation -> {
                log(tag) { "removeDeviceStorageLocation(): ${selectedItemsFlow.value.size} items" }
                val selectedItems = selectedItemsFlow.value
                if (selectedItems.isNotEmpty()) {
                    val selectedSAFItems = selectedItems
                        .filterIsInstance<ExplorerItem.Storage.SAF>()

                    if (selectedSAFItems.isNotEmpty()) {
                        dialogStateFlow.value = RemoveLocationConfirmation(selectedSAFItems)
                    }
                }
            }
            is ExplorerAction.Device.RenameLocation -> {
                log(tag) { "renameDeviceStorageLocation()" }
                val selectedItem = selectedItemsFlow.value
                    .filterIsInstance<ExplorerItem.Storage.SAF>()
                    .single()

                dialogStateFlow.value = LocationStorageName(
                    locationId = selectedItem.location.id,
                    currentName = selectedItem.location.userLabel,
                )
            }
            is ExplorerAction.Trash.SelectAll -> {
                selectedItemsFlow.value = stateSnap.selectionState.selectableItems
            }
            is ExplorerAction.Trash.RestoreSelected -> {
                log(tag) { "restoreSelectedTrashItems(): ${selectedItemsFlow.value.size} items" }
                val selectedTrashItems = selectedItemsFlow.value
                    .filterIsInstance<ExplorerItem.Trash.Root>()

                if (selectedTrashItems.isNotEmpty()) {
                    selectedTrashItems.forEach { item ->
                        restoreTrashItem(item)
                    }
                    clearSelection()
                }
            }
            is ExplorerAction.Trash.DeletePermanentlySelected -> {
                log(tag) { "deleteSelectedTrashItems(): ${selectedItemsFlow.value.size} items" }
                val selectedTrashItems = selectedItemsFlow.value
                    .filterIsInstance<ExplorerItem.Trash.Root>()

                if (selectedTrashItems.isNotEmpty()) {
                    selectedTrashItems.forEach { item ->
                        deleteTrashItemPermanently(item)
                    }
                    clearSelection()
                }
            }
            is ExplorerAction.Trash.EmptyBin -> {
                log(tag) { "Showing empty trash confirmation" }
                dialogStateFlow.value = EmptyTrashConfirmation
            }
            is ExplorerAction.TrashNested.SelectAll -> {
                selectedItemsFlow.value = stateSnap.selectionState.selectableItems
            }
            is ExplorerAction.TrashNested.RestoreSelected -> {
                log(tag) { "restoreSelectedNestedItems(): ${selectedItemsFlow.value.size} items" }
                val selectedItems = selectedItemsFlow.value
                    .filterIsInstance<ExplorerItem.Trash.Nested>()

                if (selectedItems.isNotEmpty()) {
                    selectedItems.forEach { item ->
                        restoreNestedTrashItem(item)
                    }
                    clearSelection()
                }
            }
            is ExplorerAction.TrashNested.DeletePermanentlySelected -> {
                log(tag) { "deleteSelectedNestedItems(): ${selectedItemsFlow.value.size} items" }
                val selectedItems = selectedItemsFlow.value
                    .filterIsInstance<ExplorerItem.Trash.Nested>()

                if (selectedItems.isNotEmpty()) {
                    selectedItems.forEach { item ->
                        deleteNestedTrashItemPermanently(item)
                    }
                    clearSelection()
                }
            }
        }
    }

    fun executeActionLongClick(action: ExplorerAction) = launch {
        log(tag) { "executeActionLongClick($action)" }
        when (action) {
            is ExplorerAction.Directory.Delete -> {
                log(tag) { "longPress deleteSelectedItems(): ${selectedItemsFlow.value.size} items (forcePermDelete)" }
                val selectedItems = selectedItemsFlow.value
                if (selectedItems.isNotEmpty()) {
                    val currentLocation = state.first().currentLocation
                    if (currentLocation is ExplorerLocation.Directory) {
                        val pathsToDelete = selectedItems
                            .filterIsInstance<ExplorerItem.Lookup>()
                            .map { it.lookup.lookedUp }
                            .toSet()

                        if (pathsToDelete.isNotEmpty()) {
                            dialogEvents.emit(
                                ExplorerDialogEvent.ShowDeleteConfirmation(
                                    items = pathsToDelete,
                                    forcePermDelete = true,
                                )
                            )
                        }
                    }
                }
            }
            else -> {
                // Other actions don't support long-press, delegate to regular click
                executeAction(action)
            }
        }
    }

    // File action handlers
    private suspend fun executeOpenInNewTabs(analysis: OpenInNewTabsUseCase.AnalysisResult) {
        log(tag, INFO) { "executeOpenInNewTabs(): Opening ${analysis.totalOpenableCount} workspaces" }

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
                log(tag, INFO) { "Batch creation succeeded: $result" }
            }
            is WorkspaceAction.CreateBatch.Result.Cancelled -> {
                log(tag, INFO) { "Batch creation cancelled by user" }
            }
        }

        clearSelection()
    }

    fun openFileInEditor(item: ExplorerItem.File) = launch {
        log(tag) { "openFileInEditor(${item.lookup.name})" }
        dismissDialog()

        try {
            val action = WorkspaceAction.Create(
                type = Workspace.Type.EDITOR,
                arguments = EditorArguments.Default(filePath = item.lookup.lookedUp),
                autoFocus = true,
            )

            workspaceRemote.execute(action)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create editor workspace: ${e.asLog()}" }
            errorEvents.emit(e)
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
                log(tag, ERROR) { "Failed to open file with external app: ${e.asLog()}" }
                errorEvents.emit(e)
            }
        } else {
            log(tag, WARN) { "No app found to open file: ${item.lookup.name}" }
            errorEvents.emit(Exception("No app found to open file: ${item.lookup.name}"))
        }
    }

    fun shareFile(item: ExplorerItem.File) = launch {
        log(tag) { "shareFile(${item.lookup.name})" }
        dismissDialog()

        val shareItem = object : ShareIntentUseCase.Item {
            override val path = item.lookup.lookedUp
            override val mimeType = item.mimeType.rawType
            override val displayName = item.lookup.name
        }

        val chooserTitle = context.getString(
            eu.darken.butler.common.R.string.general_share_single_title,
            item.lookup.name
        )

        val success = shareIntentUseCase.shareWithChooser(listOf(shareItem), chooserTitle)
        if (!success) {
            errorEvents.emit(Exception("Failed to share file: ${item.lookup.name}"))
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

        // Show file info dialog
        val infoContext = ItemInfo.InfoContext.SingleFile(item)
        dialogStateFlow.value = ItemInfo(infoContext)
    }

    private suspend fun handleDialogEvent(event: ExplorerDialogEvent) {
        log(tag) { "handleDialogEvent($event)" }
        when (event) {
            is ExplorerDialogEvent.ShowCreateItem -> {
                dialogStateFlow.value = CreateItem
            }
            is ExplorerDialogEvent.ShowDeleteConfirmation -> {
                dialogStateFlow.value = DeleteConfirmation(event.items, event.forcePermDelete)
            }
            is ExplorerDialogEvent.ShowRename -> {
                dialogStateFlow.value = Rename(event.item)
                clearSelection()
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
            val command = when (result.type) {
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

            val completed = getWorkspace().execute(command)

            // Reveal the newly created item on success
            if (completed.error == null) {
                val createdPath = completed.report?.affectedPaths
                    ?.firstOrNull { it.change == Operation.Report.PathChange.Change.ADDED }
                    ?.path
                createdPath?.let { revealItems(listOf(it)) }
            }
        }
    }

    fun onDeleteConfirmed(items: Set<APath<*>>, forcePermDelete: Boolean = false) = launch {
        log(tag) { "onDeleteConfirmed($items, forcePermDelete=$forcePermDelete)" }
        dialogStateFlow.value = None

        if (items.isNotEmpty()) {
            getWorkspace().execute(
                ExplorerCommand.Delete(
                    targets = items,
                    options = ExplorerCommand.Delete.Options(forcePermDelete = forcePermDelete),
                )
            )
            clearSelection()
        }
    }

    fun onRemoveLocationConfirmed() = launch {
        val dialogState = dialogStateFlow.value as? RemoveLocationConfirmation ?: return@launch
        log(tag) { "onRemoveLocationConfirmed(): Removing ${dialogState.items.size} locations" }

        dialogStateFlow.value = None

        dialogState.items.forEach { item ->
            safLocationManager.revokePermission(item.location.id)
        }
        clearSelection()

        getWorkspace().navigate(ExplorerNavigation.Refresh)
    }

    fun onEmptyTrashConfirmed() = launch {
        log(tag) { "onEmptyTrashConfirmed()" }
        dialogStateFlow.value = None

        try {
            val deletedCount = trashManager.emptyTrash()
            log(tag, INFO) { "Emptied trash: $deletedCount items deleted" }
            getWorkspace().navigate(ExplorerNavigation.Refresh)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to empty trash: ${e.asLog()}" }
            errorEvents.emit(e)
        }
    }

    fun onLocationStorageName(name: String?) = launch {
        val dialogState = dialogStateFlow.value as? LocationStorageName ?: return@launch
        log(tag) { "onLocationStorageName(locationId=${dialogState.locationId}, name=$name)" }

        dialogStateFlow.value = None

        // Empty or whitespace-only = use default name (null)
        val trimmedName = name?.trim()?.takeIf { it.isNotEmpty() }
        safLocationManager.setLocationLabel(dialogState.locationId, trimmedName)

        clearSelection()
        delay(500.milliseconds)
        getWorkspace().navigate(ExplorerNavigation.Refresh)
    }

    fun restoreTrashItem(item: ExplorerItem.Trash.Root) = launch {
        log(tag) { "restoreTrashItem(${item.itemId})" }
        dismissDialog()

        try {
            val repoItem = trashRepo.getById(item.itemId)
            if (repoItem == null) {
                log(tag, ERROR) { "Trash item not found: ${item.itemId}" }
                errorEvents.emit(Exception("Item not found in trash"))
                return@launch
            }

            val result = trashManager.restore(listOf(repoItem))

            if (result.restored.isNotEmpty()) {
                log(tag, INFO) { "Successfully restored ${result.restored.size} items" }
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            } else if (result.failed.isNotEmpty()) {
                log(tag, ERROR) { "Failed to restore ${result.failed.size} items" }
                errorEvents.emit(Exception("Failed to restore ${item.displayName.get(context)}"))
            } else if (result.conflicts.isNotEmpty()) {
                log(tag, WARN) { "Conflicts when restoring ${result.conflicts.size} items" }
                errorEvents.emit(Exception("File already exists at original location"))
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error restoring trash item: ${e.asLog()}" }
            errorEvents.emit(e)
        }
    }

    fun deleteTrashItemPermanently(item: ExplorerItem.Trash.Root) = launch {
        log(tag) { "deleteTrashItemPermanently(${item.itemId})" }
        dismissDialog()

        try {
            val repoItem = trashRepo.getById(item.itemId)
            if (repoItem == null) {
                log(tag, ERROR) { "Trash item not found: ${item.itemId}" }
                errorEvents.emit(Exception("Item not found in trash"))
                return@launch
            }

            val deletedCount = trashManager.deletePermanently(listOf(repoItem))

            if (deletedCount > 0) {
                log(tag, INFO) { "Successfully deleted $deletedCount items permanently" }
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            } else {
                log(tag, ERROR) { "Failed to delete item permanently" }
                errorEvents.emit(Exception("Failed to delete ${item.displayName.get(context)}"))
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error deleting trash item permanently: ${e.asLog()}" }
            errorEvents.emit(e)
        }
    }

    fun restoreNestedTrashItem(item: ExplorerItem.Trash.Nested) = launch {
        log(tag) { "restoreNestedTrashItem(${item.relativePath})" }
        dismissDialog()

        try {
            val parentRepoItem = trashRepo.getById(item.parentRef.itemId)
            if (parentRepoItem == null) {
                log(tag, ERROR) { "Parent trash item not found: ${item.parentRef.itemId}" }
                errorEvents.emit(Exception("Parent item no longer exists in trash"))
                return@launch
            }

            val result = trashManager.restoreNested(parentRepoItem, item.relativePath)

            if (result.restored.isNotEmpty()) {
                log(tag, INFO) { "Successfully restored nested item" }
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            } else if (result.conflicts.isNotEmpty()) {
                log(tag, WARN) { "Conflict when restoring nested item" }
                errorEvents.emit(Exception("File already exists at original location"))
            } else {
                log(tag, ERROR) { "Failed to restore nested item" }
                errorEvents.emit(Exception("Failed to restore ${item.displayName.get(context)}"))
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error restoring nested trash item: ${e.asLog()}" }
            errorEvents.emit(e)
        }
    }

    fun deleteNestedTrashItemPermanently(item: ExplorerItem.Trash.Nested) = launch {
        log(tag) { "deleteNestedTrashItemPermanently(${item.relativePath})" }
        dismissDialog()

        try {
            val parentRepoItem = trashRepo.getById(item.parentRef.itemId)
            if (parentRepoItem == null) {
                log(tag, ERROR) { "Parent trash item not found: ${item.parentRef.itemId}" }
                errorEvents.emit(Exception("Parent item no longer exists in trash"))
                return@launch
            }

            val deletedCount = trashManager.deleteNestedPermanently(parentRepoItem, item.relativePath)

            if (deletedCount > 0) {
                log(tag, INFO) { "Successfully deleted nested item permanently" }
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            } else {
                log(tag, ERROR) { "Failed to delete nested item permanently" }
                errorEvents.emit(Exception("Failed to delete ${item.displayName.get(context)}"))
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error deleting nested trash item: ${e.asLog()}" }
            errorEvents.emit(e)
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
                    val command = when (clip.mode) {
                        ClipboardClip.Paths.Mode.COPY -> ExplorerCommand.Copy(
                            sources = clip.paths.toSet(),
                            destination = currentLocation.path,
                        )
                        ClipboardClip.Paths.Mode.CUT -> ExplorerCommand.Move(
                            sources = clip.paths.toSet(),
                            destination = currentLocation.path,
                        )
                    }
                    val completed = getWorkspace().execute(command)

                    // Reveal all added items on success (scroll to first, highlight all)
                    if (completed.error == null) {
                        val addedPaths = completed.report?.affectedPaths
                            ?.filter { it.change == Operation.Report.PathChange.Change.ADDED }
                            ?.map { it.path }
                            ?: emptyList()
                        revealItems(addedPaths)
                    }

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

    fun showConflictSheet(operationId: Operation.Id) = launch {
        log(tag) { "showConflictSheet($operationId): Requesting to show conflict sheet" }

        // Get current conflicts map
        val workspace = getWorkspace()
        val operationsState = workspace.operations.first()
        val conflicts = operationsState.pendingConflicts
        val issue = conflicts[operationId]

        if (issue != null) {
            currentConflictOperationId = operationId
            issueStateFlow.value = issue
            showIssueSheetFlow.emit(Unit)
        } else {
            log(tag, WARN) { "Cannot show conflict sheet: no conflict for operation $operationId" }
        }
    }

    fun navigateToSetup(requirements: PathRequirements) = launch {
        log(tag) { "navigateToSetup(): Opening setup for $requirements" }
        navTo(
            Nav.Main.destSetup(
                typeFilter = requirements.relevantTypes,
                satisfyingCombos = requirements.combos,
                autoCloseWhenComplete = true,
            )
        )
    }

    fun showAddStorageSheet() {
        log(tag) { "showAddStorageSheet(): Showing add storage sheet" }
        showAddStorageSheetFlow.value = true
    }

    fun dismissAddStorageSheet() {
        log(tag) { "dismissAddStorageSheet(): Dismissing add storage sheet" }
        showAddStorageSheetFlow.value = false
    }

    fun addSAFLocation() = launch {
        log(tag) { "addSAFLocation(): Launching SAF directory picker" }
        _pendingSAFPickerGrant.value = null  // Clear grant for manual addition
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
        safPickerEvents.emit(intent)
    }

    suspend fun handleSAFPickerResult(treeUri: Uri) {
        log(tag) { "handleSAFPickerResult(treeUri=$treeUri)" }
        try {
            // Take persistable permission
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val locationId = safLocationManager.grantPermission(treeUri)

            dialogStateFlow.value = LocationStorageName(locationId, currentName = null)

            // Auto-refresh if currently viewing Device location to show new SAF storage immediately
            val currentLocation = state.first().currentLocation
            if (currentLocation is ExplorerLocation.Device) {
                log(tag) { "Auto-refreshing Device location to show new SAF storage" }
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            }

            log(tag, INFO) { "Successfully added SAF location: $treeUri (locationId=$locationId)" }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to handle SAF picker result: ${e.message}" }
            errorEvents.tryEmit(e)
        }
    }

    fun launchAndroidDataSAFPicker(grant: SAFPickerGrant) = launch {
        log(tag) { "launchAndroidDataSAFPicker(): Launching SAF picker for ${grant.targetPath}" }
        _pendingSAFPickerGrant.value = grant  // Store grant for auto-labeling
        safPickerEvents.emit(grant.intent)
    }

    suspend fun handleAndroidDataSAFPickerResult(
        treeUri: Uri?,
        grant: SAFPickerGrant
    ) {
        if (treeUri == null) {
            log(tag, WARN) { "SAF picker cancelled for ${grant.targetPath}" }
            return
        }

        log(tag) { "handleAndroidDataSAFPickerResult(): $treeUri for ${grant.targetPath}" }

        try {
            // Take persistable permission
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            log(tag, INFO) { "Successfully granted SAF permission for ${grant.targetPath}" }

            // Register with SAFLocationManager and auto-label
            val locationId = safLocationManager.grantPermission(treeUri)
            log(tag, VERBOSE) { "SAF location registered with ID: $locationId" }

            // Auto-label based on target path
            val label = when {
                grant.targetPath.path.contains("/Android/data") ->
                    context.getString(R.string.explorer_saf_location_android_data_label)
                grant.targetPath.path.contains("/Android/obb") ->
                    context.getString(R.string.explorer_saf_location_android_obb_label)
                else -> null
            }

            if (label != null) {
                safLocationManager.setLocationLabel(locationId, label)
                log(tag) { "Auto-labeled SAF location as: $label" }
            }

            // Convert to SAF path and navigate there
            val safPath = safLocationManager.toSAFPath(grant.targetPath)

            if (safPath != null) {
                log(tag) { "Navigating to SAF path: $safPath" }
                getWorkspace().navigate(Directory(safPath))
            } else {
                log(tag, WARN) { "Failed to convert ${grant.targetPath} to SAFPath after permission grant" }
                // Fallback: just refresh current location
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to handle Android/data SAF picker result: ${e.message}" }
            errorEvents.tryEmit(e)
        } finally {
            _pendingSAFPickerGrant.value = null  // Clear grant after handling
        }
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
            val report = errorReportTool.buildReport(
                throwable = throwable,
                errorContext = "Navigation error in workspace ${id.shortTag}",
            )
            errorReportTool.copyToClipboard(report)
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

    fun validateFilename(name: String): FilenameValidator.ValidationResult {
        val currentPath = runBlocking {
            state.first().currentLocation?.let {
                when (it) {
                    is ExplorerLocation.Directory -> it.path
                    else -> null
                }
            }
        }
        return if (currentPath != null) {
            filenameValidator.validate(name, currentPath)
        } else {
            FilenameValidator.ValidationResult.Valid
        }
    }

    // Picker mode methods
    fun confirmPickerSelection() = launch {
        log(tag) { "confirmPickerSelection()" }
        val workspace = getWorkspace()
        val config = workspace.pickerConfig ?: run {
            log(tag, WARN) { "confirmPickerSelection() called but not in picker mode" }
            return@launch
        }

        val stateSnap = state.first()
        val selectedPaths = pickerHelper.extractSelectedPaths(
            config = config,
            currentLocation = stateSnap.currentLocation,
            selectedItems = stateSnap.selectionState.selectedItems,
        )

        if (selectedPaths.isEmpty()) {
            log(tag, WARN) { "No paths selected" }
            return@launch
        }

        // For SaveAs mode, also validate filename
        val filename: String? = if (config.selection is PickerConfig.Selection.SaveAs) {
            val fn = stateSnap.saveAsFilename.trim()
            if (fn.isBlank()) {
                log(tag, WARN) { "SaveAs mode requires a filename" }
                return@launch
            }
            fn
        } else {
            null
        }

        log(tag, INFO) { "Picker selection confirmed: ${selectedPaths.size} path(s), filename=$filename" }

        // Emit PickerResult event and close workspace
        workspaceRemote.returnResult(
            WorkspaceEvent.PickerResult(
                workspaceId = id,
                callerWorkspaceId = config.callerWorkspaceId,
                selectedPaths = selectedPaths,
                filename = filename,
            )
        )
    }

    fun cancelPicker() = launch {
        log(tag) { "cancelPicker()" }
        val workspace = getWorkspace()
        val config = workspace.pickerConfig
        if (config == null) {
            log(tag, WARN) { "cancelPicker() called but not in picker mode" }
            return@launch
        }

        log(tag, INFO) { "Picker cancelled" }

        // Emit cancellation event and close workspace
        workspaceRemote.cancelResult(
            workspaceId = id,
            callerWorkspaceId = config.callerWorkspaceId,
        )
    }

    fun updateSaveAsFilename(filename: String) = launch {
        log(tag) { "updateSaveAsFilename($filename)" }
        getWorkspace().updateSaveAsFilename(filename)
    }

    fun goBack() {
        log(tag) { "goBack()" }
        navigate(ExplorerNavigation.Back)
    }

    fun revealItems(paths: List<APath<*>>, highlight: Boolean = true) = launch {
        if (paths.isEmpty()) return@launch
        log(tag) { "revealItems(${paths.map { it.path }}, highlight=$highlight)" }
        revealRequests.emit(RevealRequest(paths.first(), highlight))
        if (highlight) {
            highlightedItemIds.value = paths.map { it.toPathItemId() }.toSet()
        }
    }

    private fun clearHighlights() {
        if (highlightedItemIds.value.isNotEmpty()) {
            log(tag) { "clearHighlights()" }
            highlightedItemIds.value = emptySet()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}