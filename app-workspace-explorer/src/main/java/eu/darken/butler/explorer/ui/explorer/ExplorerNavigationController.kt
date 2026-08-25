package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.smb.SmbLocationInput
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.pkgs.installer.AppInstallFormat
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerItem.Path.Companion.toPathItemId
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.TrashItemReference
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.returnResult
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Navigation and reveal/highlight handling: item taps that resolve to navigation (including
 * symlink following and picker instant-selection), path edits, back navigation and refresh.
 */
class ExplorerNavigationController(
    private val workspaceId: Workspace.Id,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val workspaceRemote: WorkspaceRemote,
    private val gatewaySwitch: GatewaySwitch,
    private val dialogs: ExplorerDialogController,
    private val favoritesRepo: ExplorerFavoritesRepo,
    private val selectedItems: () -> Set<ExplorerItem>,
    private val toggleSelection: (ExplorerItem) -> Unit,
    private val clearSelection: () -> Unit,
    private val getState: suspend () -> ExplorerWorkspaceViewModel.State,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    val revealRequests = SingleEventFlow<ExplorerWorkspaceViewModel.RevealRequest>()

    private val highlightedItemIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    val highlightedItemIds: StateFlow<Set<String>> = highlightedItemIdsFlow

    /** Location the current highlight belongs to, so arriving at it doesn't clear it again. */
    private var highlightedLocationId: String? = null

    fun navigate(item: ExplorerItem) = doLaunch {
        log(tag) { "navigate($item)" }
        when (item) {
            is ExplorerItem.Path -> when (item) {
                is ExplorerItem.Directory -> {
                    workspace().navigate(ExplorerNavigation.Target.Directory(item.lookup.lookedUp))
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
                                workspace().navigate(ExplorerNavigation.Target.Directory(target))
                                clearSelection()
                                return@doLaunch
                            } else {
                                log(tag, INFO) { "Symlink points to file: ${item.targetPath}" }
                                // Fall through to show file options dialog
                            }
                        }
                    }

                    val workspace = workspace()
                    val config = workspace.pickerConfig

                    // Archives open like folders. Pickers keep treating them as selectable files,
                    // and archives nested inside another archive only offer the options sheet.
                    // App-install bundles are zips too, but a tap on one has to behave like a tap on
                    // a plain .apk - open the viewer - so Install stays on the primary gesture.
                    val isBrowsableArchive = config == null &&
                        ArchiveFormat.fromFileName(item.lookup.name) != null &&
                        AppInstallFormat.fromFileName(item.lookup.name)?.isBundle != true &&
                        item.lookup.lookedUp !is ArchivePath
                    if (isBrowsableArchive) {
                        log(tag, INFO) { "Browsing into archive: ${item.lookup.name}" }
                        workspace.navigate(
                            ExplorerNavigation.Target.Directory(ArchivePath.root(item.lookup.lookedUp))
                        )
                        clearSelection()
                        return@doLaunch
                    }

                    // FileSingle mode: instant selection on file tap
                    if (config?.selection?.instantFileSelection == true) {
                        log(tag, INFO) { "FileSingle instant selection: ${item.lookup.name}" }
                        workspaceRemote.returnResult(
                            WorkspaceEvent.PickerResult(
                                workspaceId = workspaceId,
                                callerWorkspaceId = config.callerWorkspaceId,
                                selectedPaths = listOf(item.lookup.lookedUp),
                            )
                        )
                    } else {
                        // Normal mode or other picker modes: show file options dialog
                        dialogs.show(ExplorerDialogState.FileOptions(item))
                    }
                }
                is ExplorerItem.Peek -> {
                    // NOOP
                }
            }
            is ExplorerItem.Shortcut -> {
                workspace().navigate(item.target)
                clearSelection()
            }
            is ExplorerItem.Storage -> {
                // Opening a location whose password is gone would just fail: ask for it first.
                val needsSignIn = item is ExplorerItem.Storage.Network &&
                    item.status == ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED
                if (needsSignIn) {
                    dialogs.show(
                        ExplorerDialogState.SmbLocationForm(
                            existing = (item as ExplorerItem.Storage.Network).location
                        )
                    )
                } else {
                    workspace().navigate(item.target)
                    clearSelection()
                }
            }
            is ExplorerItem.Trash.Root -> {
                if (selectedItems().isNotEmpty()) {
                    toggleSelection(item)
                } else if (item.trashLookup?.fileType == FileType.DIRECTORY && item.isAvailable) {
                    // Navigate into trashed folder
                    val ref = TrashItemReference.from(item)
                    workspace().navigate(ExplorerNavigation.Target.Trash.Nested(ref, ""))
                    clearSelection()
                } else {
                    dialogs.show(ExplorerDialogState.TrashItemOptions(item))
                }
            }
            is ExplorerItem.Trash.Nested -> {
                if (selectedItems().isNotEmpty()) {
                    toggleSelection(item)
                } else if (item.isDirectory) {
                    // Navigate deeper into nested trash
                    workspace().navigate(ExplorerNavigation.Target.Trash.Nested(item.parentRef, item.relativePath))
                    clearSelection()
                } else {
                    // Show options for nested files
                    dialogs.show(ExplorerDialogState.TrashNestedItemOptions(item))
                }
            }
        }
    }

    fun navigateToPath(path: APath<*>) = doLaunch {
        log(tag) { "navigateToPath($path)" }
        workspace().navigate(ExplorerNavigation.Target.Directory(path))
        clearSelection()
    }

    fun navigateToEditedPath(currentPath: APath<*>, editedPath: String) {
        val trimmed = editedPath.trim()
        val newPath = when (currentPath) {
            is SAFPath -> {
                val segments = if (trimmed.isEmpty() || trimmed == "/") {
                    emptyArray()
                } else {
                    trimmed.split("/").filter { it.isNotEmpty() }.toTypedArray()
                }
                SAFPath.build(currentPath.treeRootUri, *segments)
            }
            is LocalPath -> LocalPath.build(File("/$trimmed"))
            is ArchivePath -> ArchivePath(
                container = currentPath.container,
                segments = trimmed.split("/").filter { it.isNotEmpty() && it != "." && it != ".." },
            )
            // Edited text addresses a folder below the location root, never outside it.
            is SmbPath -> {
                val segments = SmbLocationInput.splitPath(trimmed)
                if (segments.any { SmbLocationInput.pathSegmentIssue(it) != null }) {
                    log(tag, WARN) { "navigateToEditedPath(): Rejecting '$trimmed', not a usable network path" }
                    return
                }
                SmbPath(currentPath.locationId, segments)
            }
        }
        navigateToPath(newPath)
    }

    fun navigate(target: ExplorerNavigation) = doLaunch {
        log(tag) { "navigate($target)" }
        workspace().navigate(target)
        clearSelection()
    }

    fun goBack() = doLaunch {
        log(tag) { "goBack()" }
        // Capture current path before navigating back
        val currentLocation = getState().currentLocation
        val currentPath = (currentLocation as? ExplorerLocation.Directory)?.path

        // Navigate back
        workspace().navigate(ExplorerNavigation.Back)
        clearSelection()

        // Reveal the directory we came from (if applicable)
        if (currentPath != null) {
            revealItems(listOf(currentPath), highlight = false)
        }
    }

    fun revealItems(paths: List<APath<*>>, highlight: Boolean = true) = doLaunch {
        revealItemsNow(paths, highlight)
    }

    /**
     * Navigate to the Home screen (where the favorites section lives) and reveal [path] there.
     *
     * Waits for Home to actually become the current location before revealing: highlights are
     * dropped on every location change, so highlighting mid-navigation would be wiped.
     */
    suspend fun revealFavorite(path: APath<*>) {
        log(tag) { "revealFavorite(${path.path})" }
        val homeLocation = if (getState().currentLocation is ExplorerLocation.Home) {
            getState().currentLocation
        } else {
            val workspace = workspace()
            workspace.navigate(ExplorerNavigation.Target.Home)
            val arrived = withTimeoutOrNull(HOME_ARRIVAL_TIMEOUT) {
                workspace.state
                    .filterIsInstance<ExplorerWorkspace.State.Ready>()
                    .first { it.currentLocation is ExplorerLocation.Home }
            }
            if (arrived == null) {
                log(tag, WARN) { "revealFavorite: Home did not become current in time" }
                return
            }
            // Take the id from the state we waited on, not from getState(): the combined UI state
            // can still report the previous location here, which would misattribute the highlight
            // and let the arrival event clear it again.
            arrived.currentLocation
        }
        revealItemsNow(
            paths = listOf(path),
            highlight = true,
            scope = ExplorerWorkspaceViewModel.RevealRequest.Scope.Favorites,
            highlightOwner = homeLocation?.locationId,
        )
    }

    private suspend fun revealItemsNow(
        paths: List<APath<*>>,
        highlight: Boolean,
        scope: ExplorerWorkspaceViewModel.RevealRequest.Scope =
            ExplorerWorkspaceViewModel.RevealRequest.Scope.Items,
        highlightOwner: String? = null,
    ) {
        if (paths.isEmpty()) return
        log(tag) { "revealItems(${paths.map { it.path }}, highlight=$highlight, scope=$scope)" }
        revealRequests.emit(ExplorerWorkspaceViewModel.RevealRequest(paths.first(), highlight, scope))
        if (highlight) {
            highlightedLocationId = highlightOwner ?: getState().currentLocation?.locationId
            highlightedItemIdsFlow.value = paths.map { it.toPathItemId() }.toSet()
        }
    }

    /**
     * Drop highlights when the user leaves the location they were set for. [newLocationId] is the
     * location that just became current: a highlight set for it (e.g. by [revealFavorite], which
     * highlights right after arriving) must survive its own arrival event.
     */
    fun clearHighlights(newLocationId: String?) {
        if (highlightedItemIdsFlow.value.isEmpty()) return
        if (newLocationId != null && newLocationId == highlightedLocationId) return
        log(tag) { "clearHighlights($newLocationId)" }
        highlightedItemIdsFlow.value = emptySet()
        highlightedLocationId = null
    }

    /**
     * Centralized refresh: re-resolves favorites (so unavailable ones may become available
     * after a SAF re-grant or SD card remount) AND triggers the workspace re-navigation.
     * Use this from every user-initiated refresh entry point.
     */
    suspend fun refresh() {
        favoritesRepo.refresh()
        workspace().navigate(ExplorerNavigation.Refresh)
    }

    fun retryNavigation() = doLaunch {
        log(tag) { "retryNavigation()" }
        refresh()
    }

    fun dismissNavigationError() = doLaunch {
        log(tag) { "dismissNavigationError()" }
        val workspace = workspace()
        if (getState().canGoBack) {
            workspace.navigate(ExplorerNavigation.Back)
        } else {
            workspace.navigate(ExplorerNavigation.Target.Home)
        }
    }

    companion object {
        /** How long [revealFavorite] waits for the Home screen before giving up on the reveal. */
        private val HOME_ARRIVAL_TIMEOUT = 2.seconds
    }
}
