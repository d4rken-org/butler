package eu.darken.butler.explorer.ui.explorer

import android.content.Context
import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorIncidentStore
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.saf.SAFPickerIntentBuilder
import eu.darken.butler.common.storage.saf.StorageProviderSuggester
import eu.darken.butler.common.storage.saf.StorageProviderSuggestion
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.files.smb.SmbConnectionTester
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine as combineMany
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.pkgs.installer.AppInstallEvent
import eu.darken.butler.common.pkgs.installer.AppInstallInspector
import eu.darken.butler.common.pkgs.installer.AppInstaller
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.DefaultStartLocation
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerTabViewStore
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.FileIntentHelper
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.workspace.core.preview.FolderPreviewObserver
import eu.darken.butler.workspace.core.preview.FolderPreviewResolver
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.smbSignInLocationId
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.toFileListing
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.core.favorites.FavoriteItem
import eu.darken.butler.explorer.core.favorites.FavoriteFeedback
import eu.darken.butler.explorer.core.favorites.applyFavoritePriority
import eu.darken.butler.explorer.core.ArchiveCompressionDefaults
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.sorting.ExplorerItemSorter
import eu.darken.butler.explorer.core.sorting.rules.ExplorerTabSortStore
import eu.darken.butler.explorer.core.sorting.rules.FolderSortRulesRepo
import eu.darken.butler.explorer.core.sorting.rules.SortRuleLayer
import eu.darken.butler.explorer.core.sorting.rules.TabSortRule
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey
import eu.darken.butler.explorer.ui.explorer.actions.DefaultActionProvider
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemResult
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemType
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogEvent
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.SmbLocationFormInput
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.*
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork.Capacity as SingleNetworkCapacity
import eu.darken.butler.explorer.ui.explorer.dialogs.FilterOptionsResult
import eu.darken.butler.explorer.ui.explorer.dialogs.RenameResult
import eu.darken.butler.explorer.ui.explorer.dialogs.RevealedPassword
import eu.darken.butler.explorer.ui.explorer.dialogs.SortOptionsResult
import eu.darken.butler.explorer.ui.explorer.dialogs.SortScope
import eu.darken.butler.explorer.ui.explorer.dnd.validateDropDestination
import eu.darken.butler.explorer.ui.explorer.dnd.validateTrashDrop
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.explorer.ui.explorer.util.ItemInfoCalculator
import eu.darken.butler.explorer.ui.picker.ExplorerPickerHelper
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.permissions.core.SAFPickerGrant
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.NoAppForFileException
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.ShareIntentUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.cancelResult
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.clipboard.ClipboardSettings
import eu.darken.butler.workspace.core.operations.AppInstallOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import eu.darken.butler.workspace.core.returnResult
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid
import eu.darken.butler.workspace.R as WorkspaceR

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
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
    private val upgradeRepo: UpgradeRepo,
    private val filenameValidator: FilenameValidator,
    private val gatewaySwitch: GatewaySwitch,
    internal val safLocationManager: SAFLocationManager,
    private val safPickerIntentBuilder: SAFPickerIntentBuilder,
    private val storageProviderSuggester: StorageProviderSuggester,
    private val smbLocationManager: SmbLocationManager,
    private val smbCredentialStore: SmbCredentialStore,
    private val smbConnectionTester: SmbConnectionTester,
    private val trashManager: TrashManager,
    private val trashRepo: TrashRepo,
    private val itemInfoCalculator: ItemInfoCalculator,
    private val pickerHelper: ExplorerPickerHelper,
    private val favoritesRepo: ExplorerFavoritesRepo,
    private val operationFocusRequest: OperationFocusRequest,
    private val folderPreviewResolver: FolderPreviewResolver,
    private val storageEnvironment: StorageEnvironment,
    private val folderSortRules: FolderSortRulesRepo,
    private val tabSortStore: ExplorerTabSortStore,
    private val tabViewStore: ExplorerTabViewStore,
    private val appInstallInspector: AppInstallInspector,
    private val appInstaller: AppInstaller,
    private val appInstallOperationFactory: AppInstallOperation.Factory,
    private val operationsManager: OperationsManager,
    private val json: Json,
    private val errorIncidentStore: ErrorIncidentStore,
    chromeFactory: WorkspacePageChrome.Factory,
) : ViewModel4(dispatchers, logTag("Explorer", "Workspace", id.shortTag, "Page")) {

    private val chrome = chromeFactory.create(id, vmScope)

    val folderPreviewObserver: FolderPreviewObserver = folderPreviewResolver::observe

    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit = { block -> launch(block = block) }

    private val dialogs = ExplorerDialogController(
        filterState = { viewSettings.filterState.value },
        useRegexPatterns = { cachedUseRegexPatterns },
        clearSelection = ::clearSelection,
        tag = tag,
    )
    private val focus = ExplorerFocusController()
    private val favoritesController = ExplorerFavoritesController(
        favoritesRepo = favoritesRepo,
        scope = vmScope,
        doLaunch = doLaunch,
        isPickerActive = { cachedPickerConfig != null },
        revealFavorite = { path -> navigation.revealFavorite(path) },
        tag = tag,
    )
    private val conflicts = ExplorerOperationConflictController(
        workspaceId = id,
        pendingConflicts = chrome.pendingConflicts,
        operationFocusRequest = operationFocusRequest,
        workspace = ::getWorkspace,
        doLaunch = doLaunch,
        tag = tag,
    )
    private val safLocations = ExplorerSafLocationController(
        context = context,
        safLocationManager = safLocationManager,
        safPickerIntentBuilder = safPickerIntentBuilder,
        storageProviderSuggester = storageProviderSuggester,
        dialogs = dialogs,
        scope = vmScope,
        workspace = ::getWorkspace,
        currentLocation = { getState().currentLocation },
        clearSelection = ::clearSelection,
        onError = { errorEvents.tryEmit(it) },
        doLaunch = doLaunch,
        tag = tag,
    )
    private val smbLocations = ExplorerSmbLocationController(
        locationManager = smbLocationManager,
        credentialStore = smbCredentialStore,
        connectionTester = smbConnectionTester,
        dialogs = dialogs,
        workspace = ::getWorkspace,
        currentLocation = { cachedCurrentLocation },
        clearSelection = ::clearSelection,
        onError = { errorEvents.tryEmit(it) },
        doLaunch = doLaunch,
        tag = tag,
    )
    private val trash = ExplorerTrashController(
        context = context,
        trashManager = trashManager,
        trashRepo = trashRepo,
        workspace = ::getWorkspace,
        clearSelection = ::clearSelection,
        onError = { errorEvents.tryEmit(it) },
        doLaunch = doLaunch,
        tag = tag,
    )
    private val selection: ExplorerSelectionController = ExplorerSelectionController(
        pickerConfig = { cachedPickerConfig },
        workspace = ::getWorkspace,
        selectableItems = { getState().selectionState.selectableItems },
        currentLocationId = { cachedCurrentLocation?.locationId },
        navigate = { navigate(it) },
        doLaunch = doLaunch,
        tag = tag,
    )
    private val navigation: ExplorerNavigationController = ExplorerNavigationController(
        workspaceId = id,
        workspace = ::getWorkspace,
        workspaceRemote = workspaceRemote,
        gatewaySwitch = gatewaySwitch,
        dialogs = dialogs,
        favoritesRepo = favoritesRepo,
        selectedItems = { selection.selectedItems.value },
        toggleSelection = { selection.toggle(it) },
        clearSelection = ::clearSelection,
        getState = ::getState,
        doLaunch = doLaunch,
        tag = tag,
    )

    /** What the open network info sheet loads; both end when that sheet goes away. */
    @Volatile private var networkRevealJob: Job? = null
    @Volatile private var networkCapacityJob: Job? = null

    val issueState: StateFlow<Issue?> get() = conflicts.issueState
    val showIssueSheet: StateFlow<Boolean> get() = conflicts.showIssueSheet
    val showAddStorageSheet get() = safLocations.showAddStorageSheet

    val dialogEvents get() = dialogs.events

    val safPickerEvents get() = safLocations.safPickerEvents

    val shareIntentEvent = chrome.shareIntentEvent

    val pendingErrorShare = chrome.pendingErrorShare

    val toastEvents = SingleEventFlow<CaString>()

    // Reveal and highlight functionality
    data class RevealRequest(
        val path: APath<*>,
        val highlight: Boolean = true,
        val scope: Scope = Scope.Items,
        val highlightDurationMs: Long = 2000L,
    ) {
        /** Which part of the page content holds the reveal target. */
        enum class Scope { Items, Favorites }
    }

    val revealRequests get() = navigation.revealRequests

    val pendingSAFPickerGrant: Flow<SAFPickerGrant?> get() = safLocations.pendingSAFPickerGrant

    private val workspaceSource: Flow<ExplorerWorkspace?> =
        workspaceProvider.retrieve(id).map { it as ExplorerWorkspace? }
    private val itemSorter = itemSorterFactory.create(id)
    private suspend fun getWorkspace() = workspaceSource.filterNotNull().first()
    private suspend fun getState(): State = state.filterNotNull().first()

    private val workspaceState: Flow<ExplorerWorkspace.State?> = workspaceSource.flatMapLatest { ws ->
        ws?.state ?: flowOf(null)
    }

    private val workspaceReadyState: Flow<ExplorerWorkspace.State.Ready?> = workspaceState.map {
        it as? ExplorerWorkspace.State.Ready
    }

    // Declared here rather than beside the other controllers: it consumes workspaceReadyState, so a
    // property reference from above would read an uninitialized field.
    private val viewSettings = ExplorerViewSettingsController(
        explorerSettings = explorerSettings,
        folderSortRules = folderSortRules,
        tabSortStore = tabSortStore,
        tabViewStore = tabViewStore,
        json = json,
        workspaceId = id,
        currentLocation = workspaceReadyState.map { it?.currentLocation },
        scope = vmScope,
        doLaunch = doLaunch,
    )

    // Picker configuration (null for non-picker workspaces)
    private val pickerConfigFlow: Flow<PickerConfig?> = workspaceSource.map { it?.pickerConfig }
    @Volatile private var cachedPickerConfig: PickerConfig? = null
    @Volatile private var cachedUseRegexPatterns: Boolean = explorerSettings.useRegexPatterns.valueBlocking
    @Volatile private var cachedCurrentLocation: ExplorerLocation? = null

    // SaveAs filename (only used in SaveAs picker mode)
    private val saveAsFilenameFlow: Flow<String> = workspaceSource.flatMapLatest { ws ->
        ws?.saveAsFilename ?: flowOf("")
    }

    init {
        pickerConfigFlow
            .onEach { config ->
                cachedPickerConfig = config
                // When the picker becomes active, finalize any pending favorite removal.
                // The bar is hidden in picker mode anyway; without this, returning to
                // non-picker mode within the 5s window would resurface a stale bar.
                if (config != null) {
                    favoritesController.clearFeedback()
                }
            }
            .launchInViewModel()

        explorerSettings.useRegexPatterns.flow
            .onEach { cachedUseRegexPatterns = it }
            .launchInViewModel()

        workspaceReadyState
            .map { it?.currentLocation }
            .onEach { location ->
                cachedCurrentLocation = location
                // Keep the selection anchored to what's actually on disk: items removed elsewhere
                // drop out of it, a metadata pass only swaps in the refreshed instances.
                selection.pruneAgainst(location)
            }
            .launchInViewModel()

        // A directory that fails because the password is gone or wrong opens the sign-in form
        // instead of a dead error screen; saving it refreshes the location.
        workspaceReadyState
            .distinctUntilChangedBy { it?.error }
            .onEach { state ->
                val locationId = state?.smbSignInLocationId() ?: return@onEach
                smbLocations.promptSignIn(locationId)
            }
            .launchInViewModel()

        // Handle dialog events
        dialogEvents
            .onEach { event ->
                dialogs.handle(event)
            }
            .launchInViewModel()

        // What an open network info sheet loads belongs to that sheet, so it both starts and ends
        // here: one coroutine, cancel before start, no ordering to get wrong. Keyed by sheet
        // instance because reopening the same location is a new sheet with the same locationId,
        // and a StateFlow would not report the reopen at all if it were keyed by that.
        dialogs.state
            .map { (it as? ItemInfo)?.context as? ItemInfo.InfoContext.SingleNetwork }
            .distinctUntilChangedBy { it?.sheetInstanceId }
            .onEach { sheet ->
                // Cancelling ends the delivery of a result, not the work behind it: the capacity
                // read is blocking socket I/O that only its own connect and request timeouts can
                // stop. A late result is harmless because updateSingleNetwork checks who it
                // belongs to.
                networkRevealJob?.cancel()
                networkRevealJob = null
                networkCapacityJob?.cancel()
                networkCapacityJob = null
                if (sheet == null) return@onEach
                loadNetworkCapacity(sheet, getState().currentLocation?.items)
            }
            .launchInViewModel()

        // Clear highlights when navigating to a different location
        workspaceReadyState
            .map { it?.currentLocation?.locationId }
            .distinctUntilChanged()
            .onEach { navigation.clearHighlights(it) }
            .launchInViewModel()

        // Favorite-path changes can reorder the directory listing (favorited dirs move
        // to the top). Drop any focused-item index so focus doesn't silently land on
        // a different item after reorder. (StateFlow self-deduplicates, so no
        // distinctUntilChanged needed; drop(1) skips the initial value.)
        favoritesRepo.favoritePaths
            .drop(1)
            .onEach { focus.clear() }
            .launchInViewModel()

        // A "tap to resolve" conflict notification routes here (see the controller).
        conflicts.focusRequestHandler.launchInViewModel()

        revealCreationHint()
    }

    /**
     * A tab opened to show one particular file ("Show in Explorer", "Open saved file") highlights it
     * once the tab has actually arrived at the folder it was created for.
     *
     * Waiting for the arrival is what makes the highlight stick: it is dropped on every location
     * change, and the reveal needs the listing it highlights in, so acting while the first
     * navigation or its load is still running would wipe or miss the item.
     *
     * The hint is only PEEKED at here and claimed once the items are on screen: a page torn down
     * while waiting - the tab is scrolled out of the pager, the ViewModel is recreated - would
     * otherwise carry the hint to the grave and the file would never be highlighted.
     */
    private fun revealCreationHint() = launch {
        val workspace = workspaceSource.filterNotNull().first()
        val hint = workspace.peekRevealHint() ?: return@launch
        log(tag) { "revealCreationHint(): waiting for ${hint.location.path} to reveal ${hint.path.path}" }

        awaitLoadedLocation(workspace.state, hint.location)

        if (workspace.consumeRevealHint() == null) {
            log(tag) { "revealCreationHint(): hint was already claimed elsewhere" }
            return@launch
        }
        navigation.revealItems(listOf(hint.path), highlight = true)
    }

    override fun onCleared() {
        conflicts.onCleared()
        // A pending overwrite confirmation still holds an un-submitted password; wipe it on teardown.
        (dialogs.current() as? CompressOverwriteConfirmation)?.pending?.options?.password?.fill(Char(0))
        super.onCleared()
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
        /**
         * The resolution that belongs to [locationId], or null while the new location's rules are
         * still being looked up. Everything sort-related is derived from this, so the UI can never
         * pair one folder with another folder's rule.
         */
        val resolvedSort: ExplorerViewSettingsController.ResolvedSort? = null,
        val sortSettings: SortSettings = SortSettings(),
        val trashEnabled: Boolean = false,
        val fileOpenActionsEnabled: Boolean = true,
        /** A picker returns a network location, it does not add or remove one. */
        val networkManagementEnabled: Boolean = true,
        val saveAsFilename: String = "",
        val disabledItems: Set<ExplorerItem> = emptySet(),
        val canConfirmSelection: Boolean = true,
        val highlightedItemIds: Set<String> = emptySet(),
        val focusedItemIndex: Int? = null,
        val unfilteredItemCount: Int = 0,
        val favorites: List<FavoriteItem> = emptyList(),
        val favoritePaths: List<APath<*>> = emptyList(),
        val showHomeFavoritesSection: Boolean = false,
        val favoriteFeedback: FavoriteFeedback? = null,
        /**
         * A reload of the location that is already on screen - pull-to-refresh, the action bar's
         * refresh, a SAF re-grant, a trash operation. Loads for a new target are covered by the
         * skeleton rows instead and do not count, which is why this is reported by [BrowsingEngine]
         * from what triggered the load rather than derived from progress and items here: a loader
         * publishes items well before it clears its progress, so the two are not distinguishable
         * after the fact.
         */
        val isRefreshing: Boolean = false,
        /**
         * Counts refreshes. Everything from here to the UI conflates, and a refresh of unchanged
         * content can start and finish in between two of those steps - [isRefreshing] alone would
         * then never be seen as true and the refresh would produce no visible feedback at all.
         */
        val refreshId: Int = 0,
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

    // Sorted/filtered items, shared to prevent duplicate processing
    private val processedItemsFlow: Flow<List<ExplorerItem>?> = combineMany(
        workspaceReadyState
            .map { it?.currentLocation?.items }
            .distinctUntilChanged { old, new -> old.hasSameItemsAs(new) },
        viewSettings.resolvedSort,
        viewSettings.filterState,
        explorerSettings.useRegexPatterns.flow,
        favoritesRepo.favoritePaths,
        workspaceReadyState.map { it?.currentLocation }.distinctUntilChanged { a, b -> a?.locationId == b?.locationId },
        pickerConfigFlow,
    ) { items, resolvedSort, filterState, useRegexPatterns, favoritePaths, location, pickerConfig ->
        // flatMapLatest does not clear this combine's last sort value, so items are only paired with
        // a resolution that was computed for the location they came from - otherwise the next
        // folder's listing would briefly render under the previous folder's sort.
        if (resolvedSort == null || resolvedSort.locationKey != location?.locationId) return@combineMany null
        items
            ?.let { viewSettings.applyFilters(it, filterState, useRegexPatterns) }
            ?.let { itemSorter.sortItems(it, resolvedSort.resolution.settings) }
            ?.let { applyFavoritePriority(it, location, pickerConfig, favoritePaths) }
    }.shareIn(vmScope, SharingStarted.Lazily, replay = 1)

    init {
        // Keep the focus controller's item count in sync so wrap-around math and
        // shrink-clamping always see the same list the page renders.
        processedItemsFlow
            .onEach { focus.updateItemCount(it?.size ?: 0) }
            .launchInViewModel()

        // Paired with the incarnation that produced the items, so a listing never lands in a
        // resumed instance it was not computed for. A load in flight (null, the skeleton rows)
        // publishes empty: a viewer must not keep stepping through a folder this tab left.
        workspaceSource
            .flatMapLatest { ws -> if (ws == null) emptyFlow() else processedItemsFlow.map { ws to it } }
            .onEach { (ws, items) -> ws.publishFileListing(items?.toFileListing().orEmpty()) }
            .launchInViewModel()
    }

    // applyFavoritePriority lives in eu.darken.butler.explorer.core.favorites for
    // independent unit-testing without VM scaffolding.

    // Optimization: Selection state only updates when items or selection changes
    private val derivedSelectionStateFlow: Flow<ExplorerSelectionState> = combine(
        processedItemsFlow,
        selection.selectedItems,
        pickerConfigFlow,
    ) { items, selectedItems, pickerConfig ->
        ExplorerSelectionState(
            selectedItems = selectedItems,
            selectableItems = items?.let { pickerHelper.filterSelectableItems(it, pickerConfig) } ?: emptySet(),
        )
    }

    val state: Flow<State?> = workspaceSource.flatMapLatest { ws ->
        if (ws == null) return@flatMapLatest emptyFlow()

        workspaceState.flatMapLatest { wsState ->
            when (wsState) {
                null,
                is ExplorerWorkspace.State.Initializing,
                is ExplorerWorkspace.State.Error -> emptyFlow()

                is ExplorerWorkspace.State.Ready -> combineMany(
                    flowOf(wsState),
                    processedItemsFlow,
                    derivedSelectionStateFlow,
                    viewSettings.viewStyle,
                    dialogs.state,
                    viewSettings.resolvedSort,
                    upgradeRepo.upgradeInfo,
                    viewSettings.filterState,
                    explorerSettings.useRegexPatterns.flow,
                    explorerSettings.useBackButtonForNavigation.flow,
                    pickerConfigFlow,
                    trashManager.isEnabled,
                    saveAsFilenameFlow,
                    navigation.highlightedItemIds,
                    focus.focusedIndex,
                    favoritesRepo.favorites,
                    favoritesController.feedback,
                ) { wsStateInner, items, selectionState, viewStyle, dialogState, resolvedSort, upgradeInfo, filterState, useRegexPatterns, useBackButtonForNavigation, pickerConfig, recycleBinEnabled, saveAsFilename, highlightedItemIds, focusedItemIndex, favorites, favoriteFeedback ->
                    val disabledItems = items?.let { pickerHelper.computeDisabledItems(it, pickerConfig) } ?: emptySet()

                    // flatMapLatest does not clear this combine's last sort value: until the new
                    // location's rules resolve, the retained one belongs to the previous folder.
                    val matchedSort = resolvedSort
                        ?.takeIf { it.locationKey == wsStateInner.currentLocation?.locationId }

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
                            when (action) {
                                is ExplorerActionBarItem.Common.Filter -> {
                                    val hasActiveFilters = filterState.fileTypeFilter != FileTypeFilter.ALL
                                        || filterState.includePattern.isNotBlank()
                                        || filterState.excludePattern.isNotBlank()

                                    if (hasActiveFilters) action.copy(badge = true) else action
                                }
                                // Badged whenever a rule - saved or tab-local - decides this listing,
                                // and disabled until this location's own rules have resolved.
                                is ExplorerActionBarItem.Common.Sort -> action.copy(
                                    isEnabled = action.isEnabled && matchedSort != null,
                                    badge = matchedSort?.resolution?.winnerKey != null,
                                )
                                else -> action
                            }
                        }

                    State(
                        currentLocation = wsStateInner.currentLocation,
                        locationId = wsStateInner.currentLocation?.locationId,
                        breadcrumbs = wsStateInner.currentBreadcrumbs ?: emptyList(),
                        items = items,
                        unfilteredItemCount = wsStateInner.currentLocation?.items?.size ?: 0,
                        error = wsStateInner.error,
                        isRefreshing = wsStateInner.isRefreshing,
                        refreshId = wsStateInner.refreshId,
                        selectionState = selectionState,
                        viewStyle = viewStyle,
                        canGoBack = wsStateInner.canGoBack,
                        canGoForward = wsStateInner.canGoForward,
                        availableActions = availableActions,
                        dialogState = dialogState.withLiveNetworkItem(wsStateInner.currentLocation?.items),
                        setupRequirements = wsStateInner.currentLocation?.setupRequirements ?: PathRequirements(),
                        isPro = upgradeInfo.isPro,
                        filterState = filterState,
                        useRegexPatterns = useRegexPatterns,
                        useBackButtonForNavigation = useBackButtonForNavigation,
                        pickerConfig = pickerConfig,
                        resolvedSort = matchedSort,
                        sortSettings = matchedSort?.resolution?.settings ?: SortSettings(),
                        trashEnabled = recycleBinEnabled,
                        fileOpenActionsEnabled = pickerHelper.allowsFileOpenActions(pickerConfig),
                        networkManagementEnabled = pickerHelper.allowsNetworkManagementActions(pickerConfig),
                        saveAsFilename = saveAsFilename,
                        disabledItems = disabledItems,
                        canConfirmSelection = canConfirmSelection,
                        highlightedItemIds = highlightedItemIds,
                        focusedItemIndex = focusedItemIndex?.let { idx ->
                            items?.let { if (idx < it.size) idx else it.lastIndex.takeIf { it >= 0 } }
                        },
                        favorites = favorites,
                        favoritePaths = favoritesRepo.favoritePaths.value,
                        showHomeFavoritesSection = pickerConfig == null
                            && wsStateInner.currentLocation is ExplorerLocation.Home
                            && favorites.isNotEmpty(),
                        favoriteFeedback = favoriteFeedback,
                    )
                }
            }
        }
    }
        .distinctUntilChanged()
        .asStateFlow()

    val clipboard = chrome.clipboard.asStateFlow()

    val operations = chrome.operations.asStateFlow()

    // Guards the unbrowsable-archive card actions: prevents double-launch and drives the card's
    // busy indication while extract/download is starting or running.
    private val _archiveActionBusy = MutableStateFlow(false)
    val archiveActionBusy: StateFlow<Boolean> get() = _archiveActionBusy

    // Operation dialogs live here rather than in the page: the page and its overlays are siblings,
    // so a `remember` in the page would be a different instance from the one the overlays read.
    private val _operationDialogState = MutableStateFlow<OperationDialogState>(OperationDialogState.None)
    val operationDialogState: StateFlow<OperationDialogState> = _operationDialogState

    private val _cancelOperationConfirmation = MutableStateFlow<Operation.Id?>(null)
    val cancelOperationConfirmation: StateFlow<Operation.Id?> = _cancelOperationConfirmation

    fun showOperationDetails(operationId: Operation.Id) {
        _operationDialogState.value = OperationDialogState.OperationDetails(operationId)
    }

    fun dismissOperationDialog() {
        _operationDialogState.value = OperationDialogState.None
    }

    fun requestCancelOperation(operationId: Operation.Id) {
        _operationDialogState.value = OperationDialogState.None
        _cancelOperationConfirmation.value = operationId
    }

    fun dismissCancelOperationConfirmation() {
        _cancelOperationConfirmation.value = null
    }

    fun navigate(item: ExplorerItem) = navigation.navigate(item)

    fun navigateToPath(path: APath<*>) = navigation.navigateToPath(path)

    fun navigateToEditedPath(currentPath: APath<*>, editedPath: String) =
        navigation.navigateToEditedPath(currentPath, editedPath)

    fun navigate(target: ExplorerNavigation) = navigation.navigate(target)

    fun toggleItemSelection(item: ExplorerItem) = selection.toggle(item)

    fun setSelection(items: Set<ExplorerItem>) = selection.set(items)

    fun onItemClick(item: ExplorerItem) = selection.onItemClick(item)

    fun onItemLongClick(item: ExplorerItem) = selection.onItemLongClick(item)

    fun onFavoriteClick(fav: FavoriteItem) {
        log(tag) { "onFavoriteClick($fav)" }
        when (val s = fav.state) {
            is FavoriteItem.State.Resolving -> {
                // Lookup not yet completed; ignore the tap until resolution finishes.
            }
            is FavoriteItem.State.Unavailable -> launch {
                errorEvents.emit(IllegalStateException(context.getString(R.string.explorer_favorites_unavailable_toast)))
            }
            is FavoriteItem.State.Available -> onItemClick(s.item)
        }
    }

    fun onFavoriteRemove(fav: FavoriteItem) = favoritesController.removeFromHome(fav)

    fun onFavoriteFeedbackAction() = favoritesController.onFeedbackAction()

    fun clearSelection() = selection.clear()

    fun selectAll() = selection.selectAll()

    fun selectAllFolders() = selection.selectAllFolders()

    fun selectAllFiles() = selection.selectAllFiles()

    // Focus navigation methods (wrap-around math lives in FocusNavigationState)
    fun moveFocusUp() = focus.moveUp()

    fun moveFocusDown() = focus.moveDown()

    fun moveFocusLeft(gridColumns: Int) = focus.moveLeft(gridColumns)

    fun moveFocusRight(gridColumns: Int) = focus.moveRight(gridColumns)

    fun moveFocusToFirst() = focus.moveToFirst()

    fun moveFocusToLast() = focus.moveToLast()

    fun clearFocus() = focus.clear()

    fun deleteFocusedItem(initialPermanentDelete: Boolean = false) = launch {
        val stateSnap = getState()
        val focusedIndex = stateSnap.focusedItemIndex ?: return@launch
        val focusedItem = stateSnap.items?.getOrNull(focusedIndex) as? ExplorerItem.Lookup ?: return@launch
        if (stateSnap.currentLocation !is ExplorerLocation.Directory) return@launch
        // Archive contents are read-only; the keyboard-shortcut path bypasses action-bar gating.
        if (focusedItem.path is ArchivePath) return@launch

        log(tag) { "deleteFocusedItem(initialPermanentDelete=$initialPermanentDelete): ${focusedItem.lookup.name}" }
        dialogEvents.emit(
            ExplorerDialogEvent.ShowDeleteConfirmation(
                items = setOf(focusedItem.lookup.lookedUp),
                initialPermanentDelete = initialPermanentDelete,
            )
        )
    }

    fun permanentDeleteSelectedItems() = launch {
        val stateSnap = getState()
        val selectedItems = selection.selectedItems.value
        if (selectedItems.isEmpty()) return@launch
        if (stateSnap.currentLocation !is ExplorerLocation.Directory) return@launch

        val pathsToDelete = selectedItems
            .filterIsInstance<ExplorerItem.Lookup>()
            // Archive contents are read-only; the Shift+Delete shortcut bypasses action-bar gating.
            .filter { it.path !is ArchivePath }
            .map { it.lookup.lookedUp }
            .toSet()

        if (pathsToDelete.isNotEmpty()) {
            log(tag) { "permanentDeleteSelectedItems(): ${pathsToDelete.size} items" }
            dialogEvents.emit(
                ExplorerDialogEvent.ShowDeleteConfirmation(
                    items = pathsToDelete,
                    initialPermanentDelete = true,
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
                log(tag) { "copySelectedItems(): ${selection.selectedItems.value.size} items" }
                val selected = selection.selectedItems.value
                if (selected.isEmpty()) return@launch
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.COPY,
                    origin = getWorkspace().id,
                    paths = selected
                        .filterIsInstance<ExplorerItem.Lookup>()
                        .map { it.lookup },
                )
                clipboardRepo.add(clip)
                clearSelection()
            }
            is ExplorerActionBarItem.Directory.Cut -> {
                log(tag) { "cutSelectedItems(): ${selection.selectedItems.value.size} items" }
                val selected = selection.selectedItems.value
                if (selected.isEmpty()) return@launch
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.CUT,
                    origin = getWorkspace().id,
                    paths = selected
                        .filterIsInstance<ExplorerItem.Lookup>()
                        .map { it.lookup },
                )
                clipboardRepo.add(clip)
                clearSelection()
            }
            is ExplorerActionBarItem.Directory.Delete -> {
                log(tag) { "deleteSelectedItems(): ${selection.selectedItems.value.size} items" }
                val selectedItems = selection.selectedItems.value
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
            is ExplorerActionBarItem.Directory.Compress -> {
                val currentLocation = getState().currentLocation as? ExplorerLocation.Directory ?: return@launch
                val sources = selection.selectedItems.value
                    .filterIsInstance<ExplorerItem.Lookup>()
                    .map { it.lookup.lookedUp }
                    .toSet()
                if (sources.isEmpty()) return@launch
                val suggestedName = sources.singleOrNull()?.name ?: currentLocation.path.name
                val defaults = explorerSettings.compressDefaults.value()
                dialogs.show(
                    CompressOptions(
                        sources = sources,
                        destinationDir = currentLocation.path,
                        suggestedName = suggestedName.ifEmpty { "archive" },
                        defaultFormat = defaults.format,
                        defaultPreset = defaults.level,
                    )
                )
            }
            is ExplorerActionBarItem.Directory.Extract -> {
                val archives = selection.selectedItems.value
                    .filterIsInstance<ExplorerItem.RegularFile>()
                    .map { it.lookup.lookedUp }
                clearSelection()
                extractArchives(archives)
            }
            is ExplorerActionBarItem.Directory.Share -> {
                log(tag) { "shareSelectedItems(): ${selection.selectedItems.value.size} items" }

                val selectedFiles = selection.selectedItems.value.filterIsInstance<ExplorerItem.File>()
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
                selection.set(stateSnap.selectionState.selectableItems)
            }
            is ExplorerActionBarItem.Directory.DeselectAll -> {
                selection.clear()
            }
            is ExplorerActionBarItem.Directory.OpenInNewTabs -> {
                log(tag) { "openInNewTabs(): ${selection.selectedItems.value.size} items" }
                val selectedLookups = selection.selectedItems.value.filterIsInstance<ExplorerItem.Lookup>()
                val selectedStorages = selection.selectedItems.value.filterIsInstance<ExplorerItem.Storage>()
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
                // No sheet while the location's rules are still resolving: it would edit stale ones
                buildSortOptionsState(stateSnap)?.let { dialogs.show(it) }
            }
            is ExplorerActionBarItem.Common.Filter -> {
                val filterState = viewSettings.filterState.value
                dialogs.show(
                    FilterOptions(
                        includePattern = filterState.includePattern,
                        excludePattern = filterState.excludePattern,
                        fileTypeFilter = filterState.fileTypeFilter,
                        useRegexPatterns = cachedUseRegexPatterns,
                    )
                )
            }
            is ExplorerActionBarItem.Common.UpdateViewStyle -> {
                viewSettings.updateViewStyle(action.viewStyle)
            }
            is ExplorerActionBarItem.Common.Refresh -> {
                if (selection.selectedItems.value.isNotEmpty()) {
                    log(tag) { "Refresh ignored, selection is active" }
                    return@launch
                }
                navigation.refresh()
            }
            is ExplorerActionBarItem.Common.AddToFavorites -> {
                favoritesController.addAll(action.items)
                clearSelection()
            }
            is ExplorerActionBarItem.Common.RemoveFromFavorites -> {
                favoritesController.removeAll(action.items)
                clearSelection()
            }
            is ExplorerActionBarItem.Directory.ToggleFavoriteCurrent -> {
                favoritesController.toggleCurrent(action.path)
            }
            is ExplorerActionBarItem.Common.Info -> {
                log(tag) { "showInfo(): ${selection.selectedItems.value.size} items selected" }

                // Only show info when items are selected
                if (selection.selectedItems.value.isNotEmpty()) {
                    val selectedItems = selection.selectedItems.value.toList()

                    // The display list lags behind metadata-only refreshes, resolve against the raw location items
                    val infoContext = itemInfoCalculator.calculateInfo(selectedItems, stateSnap.currentLocation?.items)
                    infoContext?.let { dialogs.show(ItemInfo(it)) }
                }
            }
            is ExplorerActionBarItem.Common.Rename -> {
                dismissDialog()
                // Archive contents are read-only; the F2 shortcut bypasses action-bar gating.
                if (action.item.path is ArchivePath) return@launch
                val event = ExplorerDialogEvent.ShowRename(
                    item = action.item.lookup.lookedUp,
                )
                dialogEvents.emit(event)
            }
            is ExplorerActionBarItem.Device.AddLocation -> {
                showAddStorageSheet()
            }
            is ExplorerActionBarItem.Device.RemoveLocation -> {
                log(tag) { "removeDeviceStorageLocation(): ${selection.selectedItems.value.size} items" }
                val selectedItems = selection.selectedItems.value
                if (selectedItems.isNotEmpty()) {
                    val selectedSAFItems = selectedItems
                        .filterIsInstance<ExplorerItem.Storage.SAF>()

                    if (selectedSAFItems.isNotEmpty()) {
                        dialogs.show(RemoveLocationConfirmation(selectedSAFItems))
                    }
                }
            }
            is ExplorerActionBarItem.Device.RenameLocation -> {
                log(tag) { "renameDeviceStorageLocation()" }
                val selectedItem = selection.selectedItems.value
                    .filterIsInstance<ExplorerItem.Storage.SAF>()
                    .single()

                dialogs.show(
                    LocationStorageName(
                        locationId = selectedItem.location.id,
                        currentName = selectedItem.location.userLabel,
                    )
                )
            }
            is ExplorerActionBarItem.Network.AddLocation -> {
                smbLocations.showAddForm()
            }
            is ExplorerActionBarItem.Network.EditLocation -> {
                val selectedItem = selection.selectedItems.value
                    .filterIsInstance<ExplorerItem.Storage.Network>()
                    .single()
                smbLocations.showEditForm(selectedItem.location.id)
            }
            is ExplorerActionBarItem.Network.RemoveLocation -> {
                val selectedItems = selection.selectedItems.value
                    .filterIsInstance<ExplorerItem.Storage.Network>()
                if (selectedItems.isNotEmpty()) smbLocations.showRemoveConfirmation(selectedItems)
            }
            is ExplorerActionBarItem.Trash.SelectAll -> {
                selection.set(stateSnap.selectionState.selectableItems)
            }
            is ExplorerActionBarItem.Trash.Restore -> {
                dismissDialog()
                trash.restoreRoot(action.items)
            }
            is ExplorerActionBarItem.Trash.DeletePermanently -> {
                dismissDialog()
                trash.deleteRootPermanently(action.items)
            }
            is ExplorerActionBarItem.Trash.EmptyBin -> {
                log(tag) { "Showing empty trash confirmation" }
                dialogs.show(EmptyTrashConfirmation)
            }
            is ExplorerActionBarItem.TrashNested.SelectAll -> {
                selection.set(stateSnap.selectionState.selectableItems)
            }
            is ExplorerActionBarItem.TrashNested.Restore -> {
                dismissDialog()
                trash.restoreNested(action.items)
            }
            is ExplorerActionBarItem.TrashNested.DeletePermanently -> {
                dismissDialog()
                trash.deleteNestedPermanently(action.items)
            }
            is ExplorerActionBarItem.File.Open -> {
                try {
                    // The viewer opens as a drill-down of this workspace: an overlay in the same
                    // pane that returns here on back. Text files still go to the Editor as a tab.
                    openFile(item = action.item, asDrillDown = true)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to open ${action.item.lookup.name}: ${e.asLog()}" }
                    errorEvents.emit(e)
                }
            }
            is ExplorerActionBarItem.File.OpenInTab -> {
                try {
                    openFile(item = action.item, asDrillDown = false)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to open ${action.item.lookup.name}: ${e.asLog()}" }
                    errorEvents.emit(e)
                }
            }
            is ExplorerActionBarItem.File.OpenInEditor -> {
                try {
                    // createAndFocus, like File.Open: one path for both rows, and it already handles
                    // AlreadyOpen by focusing the tab that has the file.
                    workspaceRemote.createAndFocus(
                        type = Workspace.Type.EDITOR,
                        arguments = EditorArguments.Default(filePath = action.item.lookup.lookedUp),
                        sourceWorkspaceId = id,
                    )
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to create editor workspace: ${e.asLog()}" }
                    errorEvents.emit(e)
                }
            }
            is ExplorerActionBarItem.File.OpenWith -> {
                val launched = fileIntentHelper.openFileWith(
                    item = action.item,
                    chooserTitle = context.getString(
                        eu.darken.butler.workspace.R.string.workspace_open_with_chooser_title
                    ),
                )
                if (!launched) {
                    log(tag, WARN) { "No other app found to open file: ${action.item.lookup.name}" }
                    errorEvents.emit(NoAppForFileException(action.item.lookup.name))
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
                    paths = listOf(action.item.lookup),
                )
                clipboardRepo.add(clip)
            }
            is ExplorerActionBarItem.File.Cut -> {
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.CUT,
                    origin = getWorkspace().id,
                    paths = listOf(action.item.lookup),
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
                dialogs.show(ItemInfo(infoContext))
            }
            is ExplorerActionBarItem.File.Extract -> {
                extractArchives(listOf(action.item.lookup.lookedUp))
            }
            is ExplorerActionBarItem.File.Install -> {
                installPackage(action.item.lookup.lookedUp)
            }
        }
    }

    /**
     * Installs an APK or app bundle.
     *
     * Inspection runs here rather than inside the operation so an unreadable, protected or
     * unsupported container is answered right away instead of behind a progress bar. The
     * unknown-sources check is a preflight for the same reason: without elevated access the platform
     * installer is the only route, and it refuses to run until Butler is an authorized install
     * source, so the user goes to the settings page and no operation is created.
     */
    private suspend fun installPackage(path: APath<*>) {
        try {
            val plan = appInstallInspector.inspect(path)
            if (!appInstaller.hasElevation() && !appInstaller.canUseSystemInstaller()) {
                log(tag, INFO) { "installPackage($path): Butler is not an authorized install source yet" }
                context.startActivity(appInstaller.unknownSourcesSettings())
                toastEvents.emit(WorkspaceR.string.workspace_install_unknown_sources_required.toCaString())
                return
            }

            val events = MutableSharedFlow<AppInstallEvent>(extraBufferCapacity = 16)
            // Subscribed before submitting, so an event emitted right at the start is not missed.
            events
                .filterIsInstance<AppInstallEvent.ObbFailed>()
                .onEach { toastEvents.emit(WorkspaceR.string.workspace_install_obb_failed.toCaString(it.reason)) }
                .launchInViewModel()

            // Closing this tab cancels the operation outright, which abandons the install session.
            // If the system's confirm dialog is already on screen the platform owns it from there
            // and may still complete the install on its own.
            operationsManager.submit(
                appInstallOperationFactory.create(
                    installOrigin = Operation.Metadata.Origin.Explorer(id),
                    plan = plan,
                    events = events,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "installPackage($path) failed: ${e.asLog()}" }
            errorEvents.emit(e)
        }
    }

    /** Extracts each archive into a subfolder beside itself (its parent directory). */
    private suspend fun extractArchives(archives: List<APath<*>>) {
        archives.forEach { archive ->
            val destinationDir = archive.parent ?: return@forEach
            val completed = getWorkspace().execute(
                ExplorerCommand.Extract(archive = archive, destinationDir = destinationDir, entries = null)
            )
            if (completed.error == null) {
                completed.report?.affectedPaths?.map { it.path }?.let { revealItems(it) }
            }
        }
    }

    /**
     * "Extract archive" from the unbrowsable-archive error card. Leaves the broken [ArchivePath]
     * target first and waits (bounded) until the workspace actually shows the parent directory -
     * navigate() only enqueues, and a reveal against the stale location would be lost.
     */
    fun extractUnbrowsableArchive(container: APath<*>) = launch {
        if (!_archiveActionBusy.compareAndSet(expect = false, update = true)) return@launch
        try {
            log(tag, INFO) { "extractUnbrowsableArchive($container)" }
            val destinationDir = container.parent
                ?: throw WriteException("Archive has no parent directory", container)
            getWorkspace().navigate(ExplorerNavigation.Target.Directory(destinationDir))
            withTimeoutOrNull(NAVIGATION_AWAIT_MS) {
                state.filterNotNull().first { current ->
                    (current.currentLocation as? ExplorerLocation.Directory)?.path == destinationDir
                }
            } ?: log(tag, WARN) { "Timed out waiting for navigation to $destinationDir" }
            val completed = getWorkspace().execute(
                ExplorerCommand.Extract(archive = container, destinationDir = destinationDir, entries = null),
            )
            if (completed.error == null) {
                // Whole-archive extraction lands in the archive-named base dir; reveal that, not
                // the individual (possibly deeply nested) extracted files.
                val baseDir = destinationDir.child(ArchiveFormat.stemOf(container.name))
                navigation.revealItems(listOf(baseDir))
            }
        } finally {
            _archiveActionBusy.value = false
        }
    }

    /**
     * "Download local copy" from the unbrowsable-archive error card: explicit copy to the local
     * Downloads folder, then browse straight into the copy - that is the promise of the action.
     */
    fun downloadArchiveCopy(container: APath<*>) = launch {
        if (!_archiveActionBusy.compareAndSet(expect = false, update = true)) return@launch
        try {
            log(tag, INFO) { "downloadArchiveCopy($container)" }
            val destinationDir = storageEnvironment.downloadsDirectory
                ?: throw WriteException("No local downloads directory available")
            val completed = getWorkspace().execute(
                ExplorerCommand.DownloadLocalCopy(source = container, destinationDir = destinationDir),
            )
            if (completed.error == null) {
                completed.report?.affectedPaths?.firstOrNull()?.path?.let { copied ->
                    getWorkspace().navigate(
                        ExplorerNavigation.Target.Directory(ArchivePath(container = copied, segments = emptyList())),
                    )
                }
            }
        } finally {
            _archiveActionBusy.value = false
        }
    }

    fun onCompressConfirmed(
        sources: Set<APath<*>>,
        destinationDir: APath<*>,
        archiveName: String,
        format: ArchiveFormat,
        preset: CompressionPreset,
        password: String?,
    ) = launch {
        log(tag) { "onCompressConfirmed($archiveName, $format, $preset)" }
        dismissDialog()
        runCatching {
            explorerSettings.compressDefaults.value(ArchiveCompressionDefaults(format = format, level = preset))
        }.onFailure { log(tag, WARN) { "Failed to persist compress defaults: ${it.asLog()}" } }

        val baseName = archiveName.trim()
        val fullName = when (ArchiveFormat.fromFileName(baseName)) {
            // Name already carries a matching extension (canonical or alias like .tgz) - keep it.
            format -> baseName
            // No archive extension: append the format's.
            null -> "$baseName.${format.displayExtension}"
            // Mismatched archive extension (e.g. leftover after switching formats): swap it.
            else -> "${ArchiveFormat.stemOf(baseName)}.${format.displayExtension}"
        }
        clearSelection()

        val command = ExplorerCommand.Compress(
            sources = sources,
            destinationDir = destinationDir,
            archiveName = fullName,
            format = format,
            options = ExplorerCommand.Compress.Options(
                preset = preset,
                password = password?.takeIf { it.isNotBlank() }?.toCharArray(),
            ),
        )

        // Best-effort pre-check for the overwrite prompt. If it can't tell (error), we proceed
        // without the prompt; the operation's own commit guard still refuses to overwrite an
        // existing archive that wasn't confirmed, so no silent data loss.
        val outputExists = runCatching {
            gatewaySwitch.exists(destinationDir.child(fullName))
        }.getOrDefault(false)
        if (outputExists) {
            dialogs.show(CompressOverwriteConfirmation(pending = command))
        } else {
            executeCompress(command)
        }
    }

    fun onCompressOverwriteConfirmed(pending: ExplorerCommand.Compress) = launch {
        log(tag) { "onCompressOverwriteConfirmed(${pending.archiveName})" }
        dismissDialog()
        executeCompress(pending.copy(overwriteConfirmed = true))
    }

    fun onCompressOverwriteCancelled(pending: ExplorerCommand.Compress) = launch {
        log(tag) { "onCompressOverwriteCancelled(${pending.archiveName})" }
        // The command never runs, so nothing else will wipe its password.
        pending.options.password?.fill(Char(0))
        dismissDialog()
    }

    private suspend fun executeCompress(command: ExplorerCommand.Compress) {
        val completed = getWorkspace().execute(command)
        if (completed.error == null) {
            completed.report?.affectedPaths?.map { it.path }?.let { revealItems(it) }
        }
    }

    // File action handlers
    /**
     * Routes a single file to the workspace type that fits it - the same classification the
     * multi-select path uses, so a text file reaches the Editor instead of a Viewer that can only
     * say it does not support the type.
     *
     * [asDrillDown] only affects the Viewer: it is the one target whose whole content is this file,
     * so it can live as an overlay in this pane. The Editor always opens as a tab of its own.
     */
    private suspend fun openFile(item: ExplorerItem.File, asDrillDown: Boolean) {
        val request = openInNewTabsUseCase.createRequest(
            item = OpenInNewTabsUseCase.Item.File(
                path = item.lookup.lookedUp,
                isText = TextFileDetector.isTextFile(item.mimeType),
            ),
            createExplorerArguments = { ExplorerArguments.Default(startPath = it) },
            createEditorArguments = { EditorArguments.Default(filePath = it) },
            createViewerArguments = {
                ViewerArguments.Default(
                    filePath = it,
                    callerWorkspaceId = if (asDrillDown) id else null,
                    listingSourceId = id,
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
        log(tag, INFO) { "executeOpenInNewTabs(): Opening ${analysis.totalOpenableCount} workspaces" }

        // Create workspace requests
        val requests = openInNewTabsUseCase.createRequests(
            analysis = analysis,
            createExplorerArguments = { path -> ExplorerArguments.Default(startPath = path) },
            createEditorArguments = { path -> EditorArguments.Default(filePath = path) },
            createViewerArguments = { path ->
                ViewerArguments.Default(filePath = path, listingSourceId = id)
            },
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
            is WorkspaceAction.CreateBatch.Result.AwaitingConfirmation -> {
                log(tag, INFO) { "Batch creation awaiting confirmation" }
            }
        }

        clearSelection()
    }

    fun dismissDialog() = dialogs.dismiss()

    fun onCreateItem(result: CreateItemResult) = launch {
        log(tag) { "onCreateItem($result)" }
        dialogs.dismiss()

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
        dialogs.dismiss()

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

    fun onRemoveLocationConfirmed() {
        val items = (dialogs.current() as? ExplorerDialogState.RemoveLocationConfirmation)?.items ?: return
        val networkItems = items.filterIsInstance<ExplorerItem.Storage.Network>()
        if (networkItems.isNotEmpty()) {
            smbLocations.onRemoveConfirmed(networkItems)
        } else {
            safLocations.onRemoveLocationConfirmed()
        }
    }

    fun onSmbLocationFormSubmit(input: SmbLocationFormInput) = smbLocations.onFormSubmit(input)

    /**
     * Puts the stored password of an open network info sheet on screen.
     *
     * The [CharArray] the vault hands over is zeroed again right away, but what reaches the sheet is
     * an immutable String, so hiding it again and dismissing the sheet can only drop the live
     * reference - earlier flow emissions and Compose snapshots may hold it until they are collected.
     * That is why this never travels through SavedStateHandle or any other serialization path.
     */
    fun onRevealNetworkPassword(locationId: Uuid) {
        log(tag) { "onRevealNetworkPassword($locationId)" }
        val sheetInstanceId = openNetworkSheet(locationId)?.sheetInstanceId ?: return
        networkRevealJob?.cancel()
        dialogs.updateSingleNetwork(locationId, sheetInstanceId) { it.copy(isRevealing = true) }
        networkRevealJob = vmScope.launch {
            val revealed = try {
                val location = smbLocationManager.get(locationId)
                location?.let {
                    withContext(dispatchers.IO) {
                        val credential = smbCredentialStore.resolve(it)
                        try {
                            RevealedPassword(String(credential.password))
                        } finally {
                            credential.wipe()
                        }
                    }
                }
            } catch (e: CancellationException) {
                // The sheet this belonged to has moved on, and its button went with it.
                throw e
            } catch (e: SmbCredentialUnavailableException) {
                // No toast: the field itself already states that the vault has nothing to produce.
                log(tag, WARN) { "onRevealNetworkPassword(): Nothing to reveal: ${e.asLog()}" }
                null
            } catch (e: Exception) {
                // An unusable keystore key - a changed screen lock - or a failed read, while the
                // field still says the password is there. That needs saying, unlike the case above.
                log(tag, ERROR) { "onRevealNetworkPassword(): Failed to reveal: ${e.asLog()}" }
                errorEvents.emit(e)
                null
            }
            dialogs.updateSingleNetwork(locationId, sheetInstanceId) {
                it.copy(revealed = revealed, isRevealing = false)
            }
        }
    }

    /**
     * Reads how full the share is, for an open info sheet only. Started from the sheet watcher,
     * which has already ended whatever the sheet before it was loading.
     *
     * This is the one place that spends an authenticated session on a location the user merely
     * looked at; drawing the Network list still costs no login attempt. Skipped entirely when there
     * is nothing to sign in with or nothing to reach, so no field appears for it.
     */
    private fun loadNetworkCapacity(sheet: ItemInfo.InfoContext.SingleNetwork, items: List<ExplorerItem>?) {
        val locationId = sheet.locationId
        val sheetInstanceId = sheet.sheetInstanceId
        val item = items
            ?.filterIsInstance<ExplorerItem.Storage.Network>()
            ?.firstOrNull { it.location.id == locationId }
            ?: return
        if (item.credentials != SmbCredentialStore.Availability.AVAILABLE) return
        if (item.endpoint.reachability == SmbEndpointState.Reachability.UNREACHABLE) return

        log(tag) { "loadNetworkCapacity($locationId)" }
        dialogs.updateSingleNetwork(locationId, sheetInstanceId) {
            it.copy(capacity = SingleNetworkCapacity.Loading)
        }
        networkCapacityJob = vmScope.launch {
            val capacity = try {
                val fileSystem = withContext(dispatchers.IO) {
                    // Same bracket a directory load uses: without an active lease on the gateway the
                    // SMB gateway's resource never opens, and the session this read logs in is left
                    // to the pool's idle sweep instead of being closed when the read is done.
                    gatewaySwitch.useRes {
                        gatewaySwitch.getFileSystem(item.location.rootPath)
                    }
                }
                val total = fileSystem.totalSpace
                val free = fileSystem.freeSpace
                if (total != null && free != null) {
                    SingleNetworkCapacity.Data(totalBytes = total, freeBytes = free)
                } else {
                    SingleNetworkCapacity.Unavailable
                }
            } catch (e: CancellationException) {
                // A cancelled read must not publish anything: the sheet it belonged to has moved on.
                throw e
            } catch (e: Exception) {
                log(tag, WARN) { "loadNetworkCapacity($locationId) failed: ${e.asLog()}" }
                SingleNetworkCapacity.Unavailable
            }
            dialogs.updateSingleNetwork(locationId, sheetInstanceId) { it.copy(capacity = capacity) }
        }
    }

    fun onHideNetworkPassword(locationId: Uuid) {
        log(tag) { "onHideNetworkPassword($locationId)" }
        val sheetInstanceId = openNetworkSheet(locationId)?.sheetInstanceId ?: return
        networkRevealJob?.cancel()
        networkRevealJob = null
        dialogs.updateSingleNetwork(locationId, sheetInstanceId) {
            it.copy(revealed = null, isRevealing = false)
        }
    }

    /**
     * The network info sheet showing right now, if it is the one for [locationId].
     *
     * Its sheet instance id is what every later write has to carry: dismissing and reopening the
     * same share are two sheets, and the first one's password must not land on the second.
     */
    private fun openNetworkSheet(locationId: Uuid): ItemInfo.InfoContext.SingleNetwork? =
        ((dialogs.current() as? ItemInfo)?.context as? ItemInfo.InfoContext.SingleNetwork)
            ?.takeIf { it.locationId == locationId }

    /**
     * The system had no activity to handle our SAF picker intent (DocumentsUI disabled or missing).
     *
     * Clears the staged grant as well: without it the next picker result would be auto-labeled for a
     * request that never reached a picker.
     */
    fun onSafPickerUnavailable(error: Throwable) = launch {
        log(tag, WARN) { "SAF picker could not be launched: ${error.asLog()}" }
        safLocations.clearPendingSAFPickerGrant()
        errorEvents.emit(error)
    }

    fun onEmptyTrashConfirmed() = launch {
        log(tag) { "onEmptyTrashConfirmed()" }
        dialogs.dismiss()
        trash.emptyTrash()
    }

    fun onLocationStorageName(name: String?) = safLocations.onLocationStorageName(name)

    fun onRename(result: RenameResult) = launch {
        log(tag) { "onRename($result)" }
        dialogs.dismiss()

        val currentLocation = getState().currentLocation as ExplorerLocation.Directory
        getWorkspace().execute(
            ExplorerCommand.Move(
                sources = setOf(result.item),
                destination = OperationPathPlan.Destination.RequestedTarget(
                    currentLocation.path.child(result.newName),
                ),
                intent = Operation.Metadata.Intent.RENAME,
            )
        )
    }

    /**
     * The sheet opens on the rule this folder owns, if it owns one, so an untouched sheet re-applies
     * exactly what is already in effect and casual re-sorting never creates a rule.
     *
     * Built from the location-matched resolution, so a sheet opened mid-navigation can never show
     * the previous folder's rule; null while this location's rules are still resolving.
     */
    private fun buildSortOptionsState(stateSnap: State): EditSortOptions? {
        val resolution = stateSnap.resolvedSort?.resolution ?: return null
        val overrides = viewSettings.tabOverrides.value
        val ownsRule = resolution.winnerIndex == 0

        return EditSortOptions(
            currentSortSettings = stateSnap.sortSettings,
            isDirectory = stateSnap.currentLocation is ExplorerLocation.Directory,
            scope = when {
                !ownsRule -> SortScope.ALL_FOLDERS
                resolution.ownsFollowDefault -> SortScope.USE_DEFAULT_HERE
                resolution.winnerSubtree -> SortScope.THIS_FOLDER_AND_SUBFOLDERS
                else -> SortScope.THIS_FOLDER
            },
            onlyThisTab = ownsRule && resolution.winnerLayer == SortRuleLayer.TAB,
            // Suppressing needs something to suppress: an ancestor rule, or a marker already here
            canUseDefaultHere = (resolution.winnerIndex ?: 0) > 0 || resolution.ownsFollowDefault,
            inheritedFrom = resolution
                .takeIf { (it.winnerIndex ?: 0) > 0 }
                ?.winnerPath
                ?.userReadablePath,
            suppressedAncestor = resolution
                .takeIf { ownsRule }
                ?.suppressedAncestorPath
                ?.userReadablePath,
            hasTabDefault = overrides.default != null,
            tabRuleCount = overrides.rules.size,
        )
    }

    /**
     * Applies the sheet's choice. Every persistent write clears the same-key tab rule (and, for
     * "All folders", the tab default too): without that the tab layer keeps winning and Apply would
     * be a silent no-op. Nothing here ever deletes an *ancestor* rule - "Use default here" is what
     * suppresses those.
     */
    fun onSortOptions(result: SortOptionsResult) = launch {
        log(tag) { "onSortOptions($result)" }
        dialogs.dismiss()

        val path = (getState().currentLocation as? ExplorerLocation.Directory)?.path
        if (path == null) {
            // Home, Device and Trash have no path to hang a rule on: write the global default and
            // drop the tab default, which would otherwise keep overriding it.
            tabSortStore.update(id) { it.copy(default = null) }
            explorerSettings.sortSettings.value(result.sortSettings)
            return@launch
        }

        val key = path.sortPathKey()
        val serializedPath = json.encodeToString(PolymorphicSerializer(APath::class), path)

        when (result.scope) {
            SortScope.ALL_FOLDERS -> if (result.onlyThisTab) {
                tabSortStore.update(id) { it.copy(default = result.sortSettings, rules = it.rules - key) }
            } else {
                folderSortRules.clear(path)
                tabSortStore.update(id) { it.copy(default = null, rules = it.rules - key) }
                explorerSettings.sortSettings.value(result.sortSettings)
            }

            SortScope.THIS_FOLDER,
            SortScope.THIS_FOLDER_AND_SUBFOLDERS -> {
                val subtree = result.scope == SortScope.THIS_FOLDER_AND_SUBFOLDERS
                if (result.onlyThisTab) {
                    tabSortStore.update(id) {
                        it.copy(
                            rules = it.rules + (
                                key to TabSortRule(
                                    settings = result.sortSettings,
                                    subtree = subtree,
                                    path = serializedPath,
                                )
                                ),
                        )
                    }
                } else {
                    folderSortRules.set(path, result.sortSettings, subtree = subtree)
                    tabSortStore.update(id) { it.copy(rules = it.rules - key) }
                }
            }

            SortScope.USE_DEFAULT_HERE -> if (result.onlyThisTab) {
                tabSortStore.update(id) {
                    it.copy(
                        rules = it.rules + (
                            key to TabSortRule(settings = null, subtree = false, path = serializedPath)
                            ),
                    )
                }
            } else {
                folderSortRules.setFollowsDefault(path)
                tabSortStore.update(id) { it.copy(rules = it.rules - key) }
            }
        }
    }

    fun clearTabSortOverrides() = launch {
        log(tag) { "clearTabSortOverrides()" }
        dialogs.dismiss()
        tabSortStore.clear(id)
    }

    fun onFilterOptions(result: FilterOptionsResult) = launch {
        log(tag) { "onFilterOptions($result)" }
        dialogs.dismiss()
        viewSettings.applyFilterState(
            FilterState(
                includePattern = result.includePattern,
                excludePattern = result.excludePattern,
                fileTypeFilter = result.fileTypeFilter,
            )
        )
    }

    fun resetFilters() = launch {
        log(tag) { "resetFilters()" }
        viewSettings.resetFilters()
    }

    fun pasteClipboard(clip: ClipboardClip) = launch {
        log(tag) { "pasteClipboard($clip)" }
        dismissDialog()
        // Archive contents are read-only; paste (Ctrl+V / clipboard bar) has no valid target inside an
        // archive, for either a path paste or a text-snippet paste.
        val pasteLocation = getState().currentLocation
        if (pasteLocation is ExplorerLocation.Directory && pasteLocation.path is ArchivePath) return@launch
        when (clip) {
            is ClipboardClip.Paths -> {
                val currentLocation = getState().currentLocation
                if (currentLocation is ExplorerLocation.Directory) {
                    val command = when (clip.mode) {
                        ClipboardClip.Paths.Mode.COPY -> ExplorerCommand.Copy(
                            sources = clip.paths.map { it.lookedUp }.toSet(),
                            destination = OperationPathPlan.Destination.Container(currentLocation.path),
                            intent = Operation.Metadata.Intent.PASTE_COPY,
                        )
                        ClipboardClip.Paths.Mode.CUT -> ExplorerCommand.Move(
                            sources = clip.paths.map { it.lookedUp }.toSet(),
                            destination = OperationPathPlan.Destination.Container(currentLocation.path),
                            intent = Operation.Metadata.Intent.PASTE_MOVE,
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
                        // Only clear a CUT clip once the move actually succeeded, otherwise the
                        // sources are lost from the clipboard while still on disk.
                        ClipboardClip.Paths.Mode.CUT -> if (completed.error == null) clipboardRepo.remove(clip.id)
                        ClipboardClip.Paths.Mode.COPY -> {
                            if (completed.error == null && clipboardSettings.removeOnPaste.value()) {
                                clipboardRepo.remove(clip.id)
                            }
                        }
                    }
                }
            }

            is ClipboardClip.Text -> {
                // Show filename dialog for text snippet paste
                dialogs.show(CreateFileFromText(clip))
            }
        }
    }

    /** Items dragged in from another workspace landed on this one, ask what to do with them. */
    fun onDragDropped(payload: WorkspaceDragPayload) = launch {
        log(tag) { "onDragDropped(${payload.items.size} items from ${payload.sourceWorkspaceId})" }
        if (validateTrashDrop(getState(), id, payload)) {
            dialogs.show(TrashDropConfirmation(payload))
            return@launch
        }
        val destination = validateDropDestination(getState(), id, payload)
        if (destination == null) {
            log(tag) { "onDragDropped(): Drop is not valid here, ignoring" }
            return@launch
        }
        dialogs.show(DropConfirmation(payload, destination))
    }

    fun onTrashDropConfirmed(payload: WorkspaceDragPayload) {
        log(tag) { "onTrashDropConfirmed(${payload.items.size} items)" }
        // Atomic claim of the dialog: a second invocation (double tap) finds it already gone.
        if (!dialogs.dismissIfCurrent(TrashDropConfirmation(payload))) return
        launch {
            val paths = payload.items.map { it.path }.toSet()
            if (paths.isEmpty()) return@launch
            getWorkspace().execute(
                ExplorerCommand.Delete(
                    targets = paths,
                    options = ExplorerCommand.Delete.Options(forcePermDelete = false),
                )
            )
            clearSelection()
            // Trash is a virtual location; BrowsingEngine's incremental FS updates key on
            // parent == current.path and don't cover it, so re-list explicitly.
            navigation.refresh()
        }
    }

    fun onDropConfirmed(payload: WorkspaceDragPayload, destination: APath<*>, move: Boolean) = launch {
        log(tag) { "onDropConfirmed(move=$move, ${payload.items.size} items to $destination)" }
        // Atomic claim of the dialog: a second invocation (double tap) finds it already gone and
        // does nothing, so exactly one command is ever launched.
        if (!dialogs.dismissIfCurrent(DropConfirmation(payload, destination))) return@launch

        if (move && !payload.allowMove) {
            log(tag, WARN) { "onDropConfirmed(): Move rejected, the source doesn't allow it" }
            return@launch
        }

        val target = validateDropDestination(getState(), id, payload)
        if (target == null || !target.matches(destination)) {
            log(tag, WARN) { "onDropConfirmed(): Destination is no longer valid" }
            errorEvents.emit(WriteException("Drop destination is no longer available", destination))
            return@launch
        }

        val sources = payload.items.map { it.path }.toSet()
        val command = if (move) {
            ExplorerCommand.Move(
                sources = sources,
                destination = OperationPathPlan.Destination.Container(target),
                intent = Operation.Metadata.Intent.DROP_MOVE,
            )
        } else {
            ExplorerCommand.Copy(
                sources = sources,
                destination = OperationPathPlan.Destination.Container(target),
                intent = Operation.Metadata.Intent.DROP_COPY,
            )
        }

        val completed = getWorkspace().execute(command)
        if (completed.error == null) {
            val addedPaths = completed.report?.affectedPaths
                ?.filter { it.change == Operation.Report.PathChange.Change.ADDED || it.change == Operation.Report.PathChange.Change.MOVED }
                ?.map { it.path }
                ?: emptyList()
            revealItems(addedPaths)
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
                    // Resolving a name conflict by renaming lands the file somewhere other than filePath.
                    val createdPath = completed.report?.affectedPaths
                        ?.firstOrNull { it.change == Operation.Report.PathChange.Change.ADDED }
                        ?.path
                    revealItems(listOfNotNull(createdPath))
                    clipboardRepo.remove(clip.id)
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to create text file: ${e.asLog()}" }
                errorEvents.emit(e)
            }
        }
    }

    fun removeClipboardEntry(clip: ClipboardClip) {
        log(tag) { "removeClipboardEntry($clip)" }
        dismissDialog()
        chrome.removeClipboardEntry(clip)
    }

    fun clearAllClipboard() = chrome.clearClipboard()

    fun resolveConflict(resolution: PathActionIssue.Resolution) = conflicts.resolve(resolution)

    fun showConflictSheet(operationId: Operation.Id) = conflicts.showSheet(operationId)

    fun dismissConflictSheet() = conflicts.dismissSheet()

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

    fun showAddStorageSheet() = safLocations.showAddStorageSheet()

    fun dismissAddStorageSheet() = safLocations.dismissAddStorageSheet()

    fun addSAFLocation() = safLocations.addSAFLocation()

    val storageSuggestions get() = safLocations.storageSuggestions

    fun addSuggestedSAFLocation(suggestion: StorageProviderSuggestion) =
        safLocations.addSuggestedSAFLocation(suggestion)

    suspend fun handleSAFPickerResult(treeUri: Uri) = safLocations.handleSAFPickerResult(treeUri)

    fun launchAndroidDataSAFPicker(grant: SAFPickerGrant) = safLocations.launchAndroidDataSAFPicker(grant)

    suspend fun handleAndroidDataSAFPickerResult(
        treeUri: Uri?,
        grant: SAFPickerGrant
    ) = safLocations.handleAndroidDataSAFPickerResult(treeUri, grant)

    fun shareError(id: Operation.Id) = chrome.shareOperationError(id)

    fun confirmErrorShare() = chrome.confirmErrorShare()

    fun dismissErrorShare() = chrome.dismissErrorShare()

    fun cancelOperation(id: Operation.Id) = chrome.cancelOperation(id)

    fun dismissOperation(id: Operation.Id) = chrome.dismissOperation(id)

    fun clearCompletedOperations() = chrome.clearCompletedOperations()

    fun showClipboardInfo(clip: ClipboardClip) {
        log(tag) { "showClipboardInfo($clip)" }
        dialogs.show(ClipboardInfo(clip))
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
        chrome.copyToSystemClipboard(path)
        toastEvents.emit(R.string.explorer_breadcrumb_copy_path_confirmation.toCaString())
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
        val error = workspaceReadyState.first()?.error ?: return@launch
        chrome.shareWorkspaceError(errorIncidentStore.getOrFreeze(error))
    }

    fun retryNavigation() = navigation.retryNavigation()

    /**
     * Pull-to-refresh entry point. Unlike [retryNavigation], which the error card's retry button
     * shares and which has to keep working, a pull is ignored while a selection is active.
     */
    fun onPullToRefresh() {
        if (selection.selectedItems.value.isNotEmpty()) {
            log(tag) { "onPullToRefresh() ignored, selection is active" }
            return
        }
        navigation.retryNavigation()
    }

    fun dismissNavigationError() = navigation.dismissNavigationError()

    fun validateFilename(name: String): FilenameValidator.ValidationResult {
        val currentPath = cachedCurrentLocation?.let {
            when (it) {
                is ExplorerLocation.Directory -> it.path
                else -> null
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
            val validation = validateFilename(fn)
            if (validation is FilenameValidator.ValidationResult.Invalid) {
                throw IllegalArgumentException(
                    "Filename contains invalid characters: ${validation.invalidChars.joinToString("")}",
                )
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

    fun goBack() = navigation.goBack()

    fun revealItems(paths: List<APath<*>>, highlight: Boolean = true) = navigation.revealItems(paths, highlight)

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }

    companion object {
        private const val NAVIGATION_AWAIT_MS = 5_000L
    }
}

/**
 * Pairs an open network info sheet with the row as it is right now.
 *
 * The dialog only remembers which location it was opened for, so a sheet opened while the address
 * was still being looked up shows the answer as soon as the probe reports it. A null item means the
 * location is no longer in the listing, e.g. because it was removed while the sheet was open.
 */
internal fun ExplorerDialogState.withLiveNetworkItem(items: List<ExplorerItem>?): ExplorerDialogState {
    val context = (this as? ItemInfo)?.context as? ItemInfo.InfoContext.SingleNetwork ?: return this
    val item = items
        ?.filterIsInstance<ExplorerItem.Storage.Network>()
        ?.firstOrNull { it.location.id == context.locationId }
    return ItemInfo(context.copy(item = item))
}

/**
 * Whether two listings would render the same, i.e. whether the newer one can be dropped.
 *
 * Lookups are compared by id and type, which lets a phase transition (Peek to Lookup) through while
 * filtering same-phase duplicates. A storage row instead carries its whole presentation - name,
 * status, address - in its own fields, so those are compared in full: by id alone a renamed or
 * re-probed location would keep rendering its old row.
 *
 * Top-level for the same reason as `applyFavoritePriority`: unit-testable without VM scaffolding.
 */
internal fun List<ExplorerItem>?.hasSameItemsAs(other: List<ExplorerItem>?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    if (size != other.size) return false
    return zip(other).all { (a, b) ->
        when {
            a is ExplorerItem.Storage || b is ExplorerItem.Storage -> a == b
            else -> a.id == b.id && a::class == b::class
        }
    }
}

/**
 * Waits for the tab to actually be SHOWING [location]: a settled [ExplorerWorkspace.State] whose
 * directory has finished loading and has a listing, not the mere request to go there. A location is
 * published as soon as the navigation starts, with no items yet, and highlighting an item that is
 * not in the listing yet does nothing.
 *
 * No deadline: the only thing waiting on it is a highlight that is claimed on arrival, so a load
 * that takes minutes still reveals, and a cancelled wait leaves the hint for the next page.
 *
 * One-shot by construction: it completes on the FIRST arrival, so a later navigation cannot make a
 * reveal fire a second time.
 */
internal suspend fun awaitLoadedLocation(
    states: Flow<ExplorerWorkspace.State>,
    location: APath<*>,
) {
    states
        .filterIsInstance<ExplorerWorkspace.State.Ready>()
        .first { state ->
            val directory = state.currentLocation as? ExplorerLocation.Directory
            directory != null &&
                directory.path.matches(location) &&
                directory.progress == null &&
                directory.items != null
        }
}
