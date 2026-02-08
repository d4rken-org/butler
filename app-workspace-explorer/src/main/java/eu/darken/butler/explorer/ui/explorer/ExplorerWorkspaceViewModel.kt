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
import eu.darken.butler.common.debug.logging.Logging.Priority.*
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
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.arguments.EditorArguments
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.DefaultStartLocation
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
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
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemResult
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemType
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogEvent
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.*
import eu.darken.butler.explorer.ui.explorer.dialogs.FilterOptionsResult
import eu.darken.butler.explorer.ui.explorer.dialogs.RenameResult
import eu.darken.butler.explorer.ui.explorer.dialogs.SortOptionsResult
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.explorer.ui.explorer.util.ItemInfoCalculator
import eu.darken.butler.explorer.ui.picker.ExplorerPickerHelper
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.permissions.core.SAFPickerGrant
import eu.darken.butler.upgrade.UpgradeRepo
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
import eu.darken.butler.workspace.core.clipboard.ClipboardSettings
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.get
import eu.darken.butler.workspace.core.returnResult
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
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
    private val clipboardSettings: ClipboardSettings,
    private val openInNewTabsUseCase: OpenInNewTabsUseCase,
    private val shareIntentUseCase: ShareIntentUseCase,
    private val fileIntentHelper: FileIntentHelper,
    private val explorerSettings: ExplorerSettings,
    itemSorterFactory: ExplorerItemSorter.Factory,
    private val operationsManager: OperationsManager,
    private val systemClipboardHelper: SystemClipboardHelper,
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
    private val focusedItemIndexFlow = MutableStateFlow<Int?>(null)
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

    val shareIntentEvent = SingleEventFlow<Intent>()

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

    private val workspaceSource: Flow<ExplorerWorkspace?> =
        workspaceProvider.retrieve(id).map { it as ExplorerWorkspace? }
    private val itemSorter = itemSorterFactory.create(id)
    private val currentSortSettings = MutableStateFlow(explorerSettings.sortSettings.valueBlocking)
    private suspend fun getWorkspace() = workspaceSource.filterNotNull().first()
    private suspend fun getState(): State = state.first()

    private val workspaceState: Flow<ExplorerWorkspace.State?> = workspaceSource.flatMapLatest { ws ->
        ws?.state ?: flowOf(null)
    }

    private val workspaceReadyState: Flow<ExplorerWorkspace.State.Ready?> = workspaceState.map {
        it as? ExplorerWorkspace.State.Ready
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
        workspaceReadyState
            .map { it?.currentLocation?.locationId }
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
        val availableActions: List<ExplorerActionBarItem> = emptyList(),
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
        val focusedItemIndex: Int? = null,
        val unfilteredItemCount: Int = 0,
    ) {
        val progress = currentLocation?.progress
        val info = currentLocation?.info

        val isFilteredEmpty: Boolean
            get() = items?.isEmpty() == true && unfilteredItemCount > 0

        fun shouldShowSelection(item: ExplorerItem): Boolean {
            // Must be selectable
            if (item !in selectionState.selectableItems) return false

            // Show in multi-select picker modes (even before any items selected)
            if (pickerConfig?.selection?.isMultiSelect == true) return true

            // Show when in selection mode (normal browsing)
            return selectionState.selectedItems.isNotEmpty()
        }
    }

    /**
     * Compares two item lists by ID and type, allowing phase transitions
     * (Peek → Lookup) while filtering same-phase duplicates.
     */
    private fun List<ExplorerItem>?.hasSameItemsAs(other: List<ExplorerItem>?): Boolean {
        if (this === other) return true
        if (this == null || other == null) return false
        if (size != other.size) return false
        return zip(other).all { (a, b) -> a.id == b.id && a::class == b::class }
    }

    // Sorted/filtered items, shared to prevent duplicate processing
    private val processedItemsFlow: Flow<List<ExplorerItem>?> = combine(
        workspaceReadyState
            .map { it?.currentLocation?.items }
            .distinctUntilChanged { old, new -> old.hasSameItemsAs(new) },
        currentSortSettings,
        filterStateFlow,
        explorerSettings.useRegexPatterns.flow,
    ) { items, sortSetting, filterState, useRegexPatterns ->
        items
            ?.let { applyFilters(it, filterState, useRegexPatterns) }
            ?.let { itemSorter.sortItems(it, sortSetting) }
    }.shareIn(vmScope, SharingStarted.Lazily, replay = 1)

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

    val state: Flow<State> = workspaceSource.flatMapLatest { ws ->
        if (ws == null) return@flatMapLatest emptyFlow()

        workspaceState.flatMapLatest { wsState ->
            when (wsState) {
                null,
                is ExplorerWorkspace.State.Initializing,
                is ExplorerWorkspace.State.Error -> emptyFlow()

                is ExplorerWorkspace.State.Ready -> combine(
                    flowOf(wsState),
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
                    focusedItemIndexFlow,
                ) { wsStateInner, items, selectionState, viewStyle, dialogState, sortSetting, upgradeInfo, filterState, useRegexPatterns, useBackButtonForNavigation, pickerConfig, recycleBinEnabled, saveAsFilename, highlightedItemIds, focusedItemIndex ->
                    val disabledItems = items?.let { pickerHelper.computeDisabledItems(it, pickerConfig) } ?: emptySet()

                    val canConfirmSelection = pickerHelper.canConfirmSelection(
                        config = pickerConfig,
                        currentLocation = wsStateInner.currentLocation,
                        selectedItems = selectionState.selectedItems,
                        saveAsFilename = saveAsFilename,
                    )

                    val rawActions = wsStateInner.currentLocation?.let {
                        actionProvider.getActions(
                            location = it,
                            selectionState = selectionState,
                            viewStyle = viewStyle,
                            trashEnabled = recycleBinEnabled,
                        )
                    } ?: emptyList()

                    val availableActions = pickerHelper.filterActionsForPicker(rawActions, pickerConfig)
                        .map { action ->
                            if (action is ExplorerActionBarItem.Common.Filter) {
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
                        currentLocation = wsStateInner.currentLocation,
                        locationId = wsStateInner.currentLocation?.locationId,
                        breadcrumbs = wsStateInner.currentBreadcrumbs ?: emptyList(),
                        items = items,
                        unfilteredItemCount = wsStateInner.currentLocation?.items?.size ?: 0,
                        error = wsStateInner.error,
                        selectionState = selectionState,
                        viewStyle = viewStyle,
                        canGoBack = wsStateInner.canGoBack,
                        canGoForward = wsStateInner.canGoForward,
                        availableActions = availableActions,
                        dialogState = dialogState,
                        setupRequirements = wsStateInner.currentLocation?.setupRequirements ?: PathRequirements(),
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
                        focusedItemIndex = focusedItemIndex?.let { idx ->
                            items?.let { if (idx < it.size) idx else it.lastIndex.takeIf { it >= 0 } }
                        },
                    )
                }
            }
        }
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
                    getWorkspace().navigate(ExplorerNavigation.Target.Directory(item.lookup.lookedUp))
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
                                getWorkspace().navigate(ExplorerNavigation.Target.Directory(target))
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
                    getWorkspace().navigate(ExplorerNavigation.Target.Trash.Nested(ref, ""))
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
                    getWorkspace().navigate(ExplorerNavigation.Target.Trash.Nested(item.parentRef, item.relativePath))
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
        getWorkspace().navigate(ExplorerNavigation.Target.Directory(path))
        clearSelection()
    }

    fun navigate(target: ExplorerNavigation) = launch {
        log(tag) { "navigate($target)" }
        getWorkspace().navigate(target)
        clearSelection()
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
        val stateSnap = getState()
        selectedItemsFlow.value = stateSnap.selectionState.selectableItems
    }

    fun selectAllFolders() = launch {
        val stateSnap = getState()
        val folders = stateSnap.selectionState.selectableItems.filter { item ->
            item is ExplorerItem.Directory ||
                (item is ExplorerItem.Trash.Nested && item.isDirectory)
        }
        selectedItemsFlow.value += folders
    }

    fun selectAllFiles() = launch {
        val stateSnap = getState()
        val files = stateSnap.selectionState.selectableItems.filter { item ->
            item is ExplorerItem.File ||
                (item is ExplorerItem.Trash.Nested && item.isFile)
        }
        selectedItemsFlow.value += files
    }

    // Focus navigation methods
    fun moveFocusUp() = launch {
        val itemCount = getState().items?.size ?: return@launch
        if (itemCount == 0) return@launch
        focusedItemIndexFlow.value = when (val current = focusedItemIndexFlow.value) {
            null -> itemCount - 1
            0 -> itemCount - 1
            else -> current - 1
        }
    }

    fun moveFocusDown() = launch {
        val itemCount = getState().items?.size ?: return@launch
        if (itemCount == 0) return@launch
        focusedItemIndexFlow.value = when (val current = focusedItemIndexFlow.value) {
            null -> 0
            itemCount - 1 -> 0
            else -> current + 1
        }
    }

    fun moveFocusLeft(gridColumns: Int) = launch {
        val itemCount = getState().items?.size ?: return@launch
        if (itemCount == 0) return@launch
        focusedItemIndexFlow.value = when {
            focusedItemIndexFlow.value == null -> itemCount - 1
            focusedItemIndexFlow.value!! < gridColumns -> itemCount - 1
            else -> focusedItemIndexFlow.value!! - gridColumns
        }
    }

    fun moveFocusRight(gridColumns: Int) = launch {
        val itemCount = getState().items?.size ?: return@launch
        if (itemCount == 0) return@launch
        focusedItemIndexFlow.value = when {
            focusedItemIndexFlow.value == null -> 0
            focusedItemIndexFlow.value!! >= itemCount - gridColumns -> 0
            else -> minOf(focusedItemIndexFlow.value!! + gridColumns, itemCount - 1)
        }
    }

    fun moveFocusToFirst() = launch {
        val itemCount = getState().items?.size ?: return@launch
        if (itemCount == 0) return@launch
        focusedItemIndexFlow.value = 0
    }

    fun moveFocusToLast() = launch {
        val itemCount = getState().items?.size ?: return@launch
        if (itemCount == 0) return@launch
        focusedItemIndexFlow.value = itemCount - 1
    }

    fun clearFocus() {
        focusedItemIndexFlow.value = null
    }

    fun deleteFocusedItem(forcePermDelete: Boolean = false) = launch {
        val stateSnap = getState()
        val focusedIndex = stateSnap.focusedItemIndex ?: return@launch
        val focusedItem = stateSnap.items?.getOrNull(focusedIndex) as? ExplorerItem.Lookup ?: return@launch
        if (stateSnap.currentLocation !is ExplorerLocation.Directory) return@launch

        log(tag) { "deleteFocusedItem(forcePermDelete=$forcePermDelete): ${focusedItem.lookup.name}" }
        dialogEvents.emit(
            ExplorerDialogEvent.ShowDeleteConfirmation(
                items = setOf(focusedItem.lookup.lookedUp),
                forcePermDelete = forcePermDelete,
            )
        )
    }

    fun permanentDeleteSelectedItems() = launch {
        val stateSnap = getState()
        val selectedItems = selectedItemsFlow.value
        if (selectedItems.isEmpty()) return@launch
        if (stateSnap.currentLocation !is ExplorerLocation.Directory) return@launch

        val pathsToDelete = selectedItems
            .filterIsInstance<ExplorerItem.Lookup>()
            .map { it.lookup.lookedUp }
            .toSet()

        if (pathsToDelete.isNotEmpty()) {
            log(tag) { "permanentDeleteSelectedItems(): ${pathsToDelete.size} items" }
            dialogEvents.emit(
                ExplorerDialogEvent.ShowDeleteConfirmation(
                    items = pathsToDelete,
                    forcePermDelete = true,
                )
            )
        }
    }

    fun executeAction(action: ExplorerActionBarItem) = launch {
        log(tag) { "executeAction(${action::class.simpleName})" }
        val stateSnap = getState()
        if (stateSnap.items == null) return@launch

        // File actions come from bottom sheets - always dismiss first
        if (action is ExplorerActionBarItem.File) {
            dismissDialog()
        }

        when (action) {
            is ExplorerActionBarItem.Directory.Create -> {
                dialogEvents.emit(ExplorerDialogEvent.ShowCreateItem)
            }
            is ExplorerActionBarItem.Directory.Rename -> {
                val item = stateSnap.selectionState.selectedItems.single() as ExplorerItem.Lookup
                val event = ExplorerDialogEvent.ShowRename(
                    item = item.lookup.lookedUp,
                )
                dialogEvents.emit(event)
            }
            is ExplorerActionBarItem.Directory.Copy -> {
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
            is ExplorerActionBarItem.Directory.Cut -> {
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
            is ExplorerActionBarItem.Directory.Delete -> {
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
            is ExplorerActionBarItem.Directory.Share -> {
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
            is ExplorerActionBarItem.Directory.SelectAll -> {
                selectedItemsFlow.value = stateSnap.selectionState.selectableItems
            }
            is ExplorerActionBarItem.Directory.DeselectAll -> {
                selectedItemsFlow.value = emptySet()
            }
            is ExplorerActionBarItem.Directory.OpenInNewTabs -> {
                log(tag) { "openInNewTabs(): ${selectedItemsFlow.value.size} items" }
                val selectedLookups = selectedItemsFlow.value.filterIsInstance<ExplorerItem.Lookup>()
                val selectedStorages = selectedItemsFlow.value.filterIsInstance<ExplorerItem.Storage>()
                if (selectedLookups.isEmpty() && selectedStorages.isEmpty()) return@launch

                // Convert Explorer items to use case items
                val items = buildList {
                    // Lookup items (files and directories inside a folder)
                    selectedLookups.forEach { item ->
                        add(
                            if (item.lookup.isDirectory) {
                                OpenInNewTabsUseCase.Item.Directory(item.lookup.lookedUp)
                            } else {
                                val isText = when (item) {
                                    is ExplorerItem.File -> TextFileDetector.isTextFile(item.mimeType)
                                    else -> TextFileDetector.isTextFile(item.lookup.lookedUp)
                                }
                                OpenInNewTabsUseCase.Item.File(item.lookup.lookedUp, isText)
                            }
                        )
                    }
                    // Storage items (USB sticks, SAF locations, etc.) - always directories
                    selectedStorages.forEach { storage ->
                        add(OpenInNewTabsUseCase.Item.Directory(storage.target.path))
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
            is ExplorerActionBarItem.Common.Sort -> {
                dialogStateFlow.value = EditSortOptions(
                    currentSortSettings = currentSortSettings.value
                )
            }
            is ExplorerActionBarItem.Common.Filter -> {
                val filterState = filterStateFlow.value
                dialogStateFlow.value = FilterOptions(
                    includePattern = filterState.includePattern,
                    excludePattern = filterState.excludePattern,
                    fileTypeFilter = filterState.fileTypeFilter,
                    useRegexPatterns = explorerSettings.useRegexPatterns.valueBlocking,
                )
            }
            is ExplorerActionBarItem.Common.UpdateViewStyle -> {
                viewStyleFlow.value = action.viewStyle
                launch {
                    explorerSettings.defaultViewStyle.value(action.viewStyle)
                }
            }
            is ExplorerActionBarItem.Common.Refresh -> {
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            }
            is ExplorerActionBarItem.Common.Info -> {
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
            is ExplorerActionBarItem.Common.Rename -> {
                dismissDialog()
                val event = ExplorerDialogEvent.ShowRename(
                    item = action.item.lookup.lookedUp,
                )
                dialogEvents.emit(event)
            }
            is ExplorerActionBarItem.Device.AddLocation -> {
                showAddStorageSheet()
            }
            is ExplorerActionBarItem.Device.RemoveLocation -> {
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
            is ExplorerActionBarItem.Device.RenameLocation -> {
                log(tag) { "renameDeviceStorageLocation()" }
                val selectedItem = selectedItemsFlow.value
                    .filterIsInstance<ExplorerItem.Storage.SAF>()
                    .single()

                dialogStateFlow.value = LocationStorageName(
                    locationId = selectedItem.location.id,
                    currentName = selectedItem.location.userLabel,
                )
            }
            is ExplorerActionBarItem.Trash.SelectAll -> {
                selectedItemsFlow.value = stateSnap.selectionState.selectableItems
            }
            is ExplorerActionBarItem.Trash.Restore -> {
                log(tag) { "restoreTrashItems(): ${action.items.size} items" }
                dismissDialog()
                if (action.items.isNotEmpty()) {
                    try {
                        val repoItems = action.items.mapNotNull { trashRepo.getById(it.itemId) }
                        if (repoItems.isEmpty()) {
                            errorEvents.emit(Exception(context.getString(R.string.explorer_trash_error_items_not_found)))
                            clearSelection()
                            return@launch
                        }
                        val result = trashManager.restore(repoItems)
                        if (result.restored.isNotEmpty()) {
                            log(tag, INFO) { "Successfully restored ${result.restored.size} items" }
                            getWorkspace().navigate(ExplorerNavigation.Refresh)
                            clearSelection()
                        } else if (result.failed.isNotEmpty()) {
                            log(tag, ERROR) { "Failed to restore ${result.failed.size} items" }
                            errorEvents.emit(Exception(context.getString(R.string.explorer_trash_error_restore_failed)))
                        } else if (result.conflicts.isNotEmpty()) {
                            log(tag, WARN) { "Conflicts when restoring ${result.conflicts.size} items" }
                            errorEvents.emit(Exception(context.getString(R.string.explorer_trash_nested_restore_conflict)))
                        }
                    } catch (e: Exception) {
                        log(tag, ERROR) { "Error restoring trash items: ${e.asLog()}" }
                        errorEvents.emit(e)
                    }
                }
            }
            is ExplorerActionBarItem.Trash.DeletePermanently -> {
                log(tag) { "deleteTrashItemsPermanently(): ${action.items.size} items" }
                dismissDialog()
                if (action.items.isNotEmpty()) {
                    try {
                        val repoItems = action.items.mapNotNull { trashRepo.getById(it.itemId) }
                        if (repoItems.isEmpty()) {
                            errorEvents.emit(Exception(context.getString(R.string.explorer_trash_error_items_not_found)))
                            clearSelection()
                            return@launch
                        }
                        val deletedCount = trashManager.deletePermanently(repoItems)
                        if (deletedCount > 0) {
                            log(tag, INFO) { "Successfully deleted $deletedCount items permanently" }
                            getWorkspace().navigate(ExplorerNavigation.Refresh)
                            clearSelection()
                        } else {
                            log(tag, ERROR) { "Failed to delete items permanently" }
                            errorEvents.emit(Exception(context.getString(R.string.explorer_trash_error_delete_failed)))
                        }
                    } catch (e: Exception) {
                        log(tag, ERROR) { "Error deleting trash items permanently: ${e.asLog()}" }
                        errorEvents.emit(e)
                    }
                }
            }
            is ExplorerActionBarItem.Trash.EmptyBin -> {
                log(tag) { "Showing empty trash confirmation" }
                dialogStateFlow.value = EmptyTrashConfirmation
            }
            is ExplorerActionBarItem.TrashNested.SelectAll -> {
                selectedItemsFlow.value = stateSnap.selectionState.selectableItems
            }
            is ExplorerActionBarItem.TrashNested.Restore -> {
                log(tag) { "restoreNestedItems(): ${action.items.size} items" }
                dismissDialog()
                if (action.items.isNotEmpty()) {
                    try {
                        var totalRestored = 0
                        // Group items by parent to reduce duplicate repo lookups
                        val itemsByParent = action.items.groupBy { it.parentRef.itemId }

                        for ((parentId, items) in itemsByParent) {
                            val parentRepoItem = trashRepo.getById(parentId)
                            if (parentRepoItem == null) {
                                log(tag, ERROR) { "Parent trash item not found: $parentId" }
                                errorEvents.emit(Exception(context.getString(R.string.explorer_trash_nested_parent_missing)))
                                continue
                            }

                            for (item in items) {
                                val result = trashManager.restoreNested(parentRepoItem, item.relativePath)
                                if (result.restored.isNotEmpty()) {
                                    totalRestored += result.restored.size
                                    log(tag, INFO) { "Successfully restored nested item" }
                                } else if (result.conflicts.isNotEmpty()) {
                                    log(tag, WARN) { "Conflict when restoring nested item" }
                                    errorEvents.emit(Exception(context.getString(R.string.explorer_trash_nested_restore_conflict)))
                                } else {
                                    log(tag, ERROR) { "Failed to restore nested item" }
                                    errorEvents.emit(
                                        Exception(
                                            context.getString(
                                                R.string.explorer_trash_nested_error_restore_failed,
                                                item.displayName.get(context)
                                            )
                                        )
                                    )
                                }
                            }
                        }
                        if (totalRestored > 0) {
                            getWorkspace().navigate(ExplorerNavigation.Refresh)
                            clearSelection()
                        }
                    } catch (e: Exception) {
                        log(tag, ERROR) { "Error restoring nested trash items: ${e.asLog()}" }
                        errorEvents.emit(e)
                    }
                }
            }
            is ExplorerActionBarItem.TrashNested.DeletePermanently -> {
                log(tag) { "deleteNestedItemsPermanently(): ${action.items.size} items" }
                dismissDialog()
                if (action.items.isNotEmpty()) {
                    try {
                        var totalDeleted = 0
                        // Group items by parent to reduce duplicate repo lookups
                        val itemsByParent = action.items.groupBy { it.parentRef.itemId }

                        for ((parentId, items) in itemsByParent) {
                            val parentRepoItem = trashRepo.getById(parentId)
                            if (parentRepoItem == null) {
                                log(tag, ERROR) { "Parent trash item not found: $parentId" }
                                errorEvents.emit(Exception(context.getString(R.string.explorer_trash_nested_parent_missing)))
                                continue
                            }

                            for (item in items) {
                                val deletedCount =
                                    trashManager.deleteNestedPermanently(parentRepoItem, item.relativePath)
                                if (deletedCount > 0) {
                                    totalDeleted += deletedCount
                                    log(tag, INFO) { "Successfully deleted nested item permanently" }
                                } else {
                                    log(tag, ERROR) { "Failed to delete nested item permanently" }
                                    errorEvents.emit(
                                        Exception(
                                            context.getString(
                                                R.string.explorer_trash_nested_error_delete_failed,
                                                item.displayName.get(context)
                                            )
                                        )
                                    )
                                }
                            }
                        }
                        if (totalDeleted > 0) {
                            getWorkspace().navigate(ExplorerNavigation.Refresh)
                            clearSelection()
                        }
                    } catch (e: Exception) {
                        log(tag, ERROR) { "Error deleting nested trash items: ${e.asLog()}" }
                        errorEvents.emit(e)
                    }
                }
            }
            is ExplorerActionBarItem.File.OpenInEditor -> {
                try {
                    val wsAction = WorkspaceAction.Create(
                        type = Workspace.Type.EDITOR,
                        arguments = EditorArguments.Default(filePath = action.item.lookup.lookedUp),
                        autoFocus = true,
                    )
                    workspaceRemote.execute(wsAction)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to create editor workspace: ${e.asLog()}" }
                    errorEvents.emit(e)
                }
            }
            is ExplorerActionBarItem.File.OpenWith -> {
                val intent = fileIntentHelper.openFileWith(action.item)
                if (intent != null && fileIntentHelper.canHandleIntent(intent)) {
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        log(tag, ERROR) { "Failed to open file with external app: ${e.asLog()}" }
                        errorEvents.emit(e)
                    }
                } else {
                    log(tag, WARN) { "No app found to open file: ${action.item.lookup.name}" }
                    errorEvents.emit(Exception("No app found to open file: ${action.item.lookup.name}"))
                }
            }
            is ExplorerActionBarItem.File.Share -> {
                val shareItem = object : ShareIntentUseCase.Item {
                    override val path = action.item.lookup.lookedUp
                    override val mimeType = action.item.mimeType.rawType
                    override val displayName = action.item.lookup.name
                }
                val chooserTitle = context.getString(
                    eu.darken.butler.common.R.string.general_share_single_title,
                    action.item.lookup.name
                )
                val success = shareIntentUseCase.shareWithChooser(listOf(shareItem), chooserTitle)
                if (!success) {
                    errorEvents.emit(Exception("Failed to share file: ${action.item.lookup.name}"))
                }
            }
            is ExplorerActionBarItem.File.Copy -> {
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.COPY,
                    origin = getWorkspace().id,
                    paths = listOf(action.item.lookup.lookedUp),
                )
                clipboardRepo.add(clip)
            }
            is ExplorerActionBarItem.File.Cut -> {
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.CUT,
                    origin = getWorkspace().id,
                    paths = listOf(action.item.lookup.lookedUp),
                )
                clipboardRepo.add(clip)
            }
            is ExplorerActionBarItem.File.Delete -> {
                dialogEvents.emit(
                    ExplorerDialogEvent.ShowDeleteConfirmation(
                        items = setOf(action.item.lookup.lookedUp)
                    )
                )
            }
            is ExplorerActionBarItem.File.ShowProperties -> {
                val infoContext = ItemInfo.InfoContext.SingleFile(action.item)
                dialogStateFlow.value = ItemInfo(infoContext)
            }
        }
    }

    fun executeActionLongClick(action: ExplorerActionBarItem) = launch {
        log(tag) { "executeActionLongClick($action)" }
        when (action) {
            is ExplorerActionBarItem.Directory.Delete -> {
                log(tag) { "longPress deleteSelectedItems(): ${selectedItemsFlow.value.size} items (forcePermDelete)" }
                val selectedItems = selectedItemsFlow.value
                if (selectedItems.isNotEmpty()) {
                    val currentLocation = getState().currentLocation
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

        val currentLocation = getState().currentLocation
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

    fun onRename(result: RenameResult) = launch {
        log(tag) { "onRename($result)" }
        dialogStateFlow.value = None

        val currentLocation = getState().currentLocation as ExplorerLocation.Directory
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

    fun resetFilters() = launch {
        log(tag) { "resetFilters()" }
        filterStateFlow.value = FilterState()
    }

    fun pasteClipboard(clip: ClipboardClip) = launch {
        log(tag) { "pasteClipboard($clip)" }
        dismissDialog()
        when (clip) {
            is ClipboardClip.Paths -> {
                val currentLocation = getState().currentLocation
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
                            ?.filter { it.change == Operation.Report.PathChange.Change.ADDED || it.change == Operation.Report.PathChange.Change.MOVED }
                            ?.map { it.path }
                            ?: emptyList()
                        revealItems(addedPaths)
                    }

                    when (clip.mode) {
                        ClipboardClip.Paths.Mode.CUT -> clipboardRepo.remove(clip.id)
                        ClipboardClip.Paths.Mode.COPY -> {
                            if (clipboardSettings.removeOnPaste.value()) {
                                clipboardRepo.remove(clip.id)
                            }
                        }
                    }
                }
            }

            is ClipboardClip.Text -> {
                // Show filename dialog for text snippet paste
                dialogStateFlow.value = CreateFileFromText(clip)
            }
        }
    }

    fun onCreateFileFromText(clip: ClipboardClip.Text, filename: String) = launch {
        log(tag) { "onCreateFileFromText(filename=$filename)" }
        dismissDialog()

        val currentLocation = getState().currentLocation
        if (currentLocation is ExplorerLocation.Directory) {
            try {
                val filePath = currentLocation.path.child(filename)
                val workspace = getWorkspace()

                // Create and write file
                val command = ExplorerCommand.CreateTextFile(
                    path = filePath,
                    content = clip.content,
                )
                val completed = workspace.execute(command)

                if (completed.error == null) {
                    log(tag, INFO) { "Created text file: $filename with ${clip.content.length} characters" }
                    revealItems(listOf(filePath))
                    clipboardRepo.remove(clip.id)
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to create text file: ${e.asLog()}" }
                errorEvents.emit(e)
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
            val currentLocation = getState().currentLocation
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
                getWorkspace().navigate(ExplorerNavigation.Target.Directory(safPath))
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

    fun shareError(id: Operation.Id) = launch {
        log(tag) { "shareError($id)" }
        val operation = operationsManager.get(id)
        if (operation == null) {
            log(tag, ERROR) { "Operation with id $id not found" }
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
            message = "${operation.metadata.title.get(context)}\n${operation.metadata.description.get(context)}",
            errorContext = "Operation error in workspace ${this@ExplorerWorkspaceViewModel.id.shortTag}",
            metadata = metadata,
        )
        val intent = errorReportTool.createShareChooserIntent(report)
        shareIntentEvent.tryEmit(intent)
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
                        getWorkspace().navigate(ExplorerNavigation.Target.Directory(parentPath))
                    }
                }
            }
            is ClipboardClip.Text -> {
                val sourcePath = clip.sourcePath
                if (sourcePath != null) {
                    val parentPath = sourcePath.parent
                    if (parentPath != null) {
                        getWorkspace().navigate(ExplorerNavigation.Target.Directory(parentPath))
                    }
                }
            }
        }
    }

    fun copyPathToSystemClipboard(path: String) = launch {
        log(tag) { "copyPathToSystemClipboard($path)" }
        systemClipboardHelper.copyToClipboard(path)
    }

    fun setAsDefaultStartLocation(target: ExplorerNavigation.Target) = launch {
        log(tag) { "setAsDefaultStartLocation($target)" }
        val location = when (target) {
            is ExplorerNavigation.Target.Home -> DefaultStartLocation.Home
            is ExplorerNavigation.Target.Device -> DefaultStartLocation.Device
            is ExplorerNavigation.Target.Directory -> DefaultStartLocation.Directory(target.path)
            else -> return@launch // Ignore Trash targets
        }
        explorerSettings.defaultStartLocation.value(location)
    }

    fun shareNavigationError() = launch {
        log(tag) { "shareNavigationError()" }
        workspaceReadyState.first()?.error?.let { throwable ->
            val report = errorReportTool.buildReport(
                throwable = throwable,
                errorContext = "Navigation error in workspace ${id.shortTag}",
            )
            val intent = errorReportTool.createShareChooserIntent(report)
            shareIntentEvent.tryEmit(intent)
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
            getState().currentLocation?.let {
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

        val stateSnap = getState()
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

    fun goBack() = launch {
        log(tag) { "goBack()" }
        // Capture current path before navigating back
        val currentLocation = getState().currentLocation
        val currentPath = (currentLocation as? ExplorerLocation.Directory)?.path

        // Navigate back
        getWorkspace().navigate(ExplorerNavigation.Back)
        clearSelection()

        // Reveal the directory we came from (if applicable)
        if (currentPath != null) {
            revealItems(listOf(currentPath), highlight = false)
        }
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