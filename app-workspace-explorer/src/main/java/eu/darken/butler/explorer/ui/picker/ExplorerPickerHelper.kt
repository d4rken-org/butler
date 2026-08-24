package eu.darken.butler.explorer.ui.picker

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.contracts.explorer.isDisabled
import eu.darken.butler.workspace.contracts.explorer.isSelectable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for picker-related computations.
 * Centralizes picker logic that was previously spread across ExplorerWorkspaceViewModel.
 */
@Singleton
class ExplorerPickerHelper @Inject constructor() {

    /**
     * Computes whether the picker confirm button should be enabled.
     * Checks all conditions: location type, filename validity, selection count, and writability.
     */
    fun canConfirmSelection(
        config: PickerConfig?,
        currentLocation: ExplorerLocation?,
        selectedItems: Set<ExplorerItem>,
        saveAsFilename: String,
    ): Boolean {
        if (config == null) return true

        return when (config.selection) {
            is PickerConfig.Selection.DirectorySingle -> {
                val atDirectory = isAtConfirmableTarget(currentLocation, selectedItems)
                val writableOk = !config.requireWritable || isWritable(currentLocation, selectedItems)
                atDirectory && writableOk
            }
            is PickerConfig.Selection.SaveAs -> {
                // SaveAs always requires writability (inherent to the operation)
                val hasValidFilename = saveAsFilename.isNotBlank()
                val atDirectory = isAtConfirmableTarget(currentLocation, selectedItems)
                hasValidFilename && atDirectory && isWritable(currentLocation, selectedItems)
            }
            is PickerConfig.Selection.DirectoryMulti,
            is PickerConfig.Selection.MixedMulti -> {
                val canSelect = selectedItems.isNotEmpty() || currentLocation is ExplorerLocation.Directory
                val writableOk = !config.requireWritable || isWritable(currentLocation, selectedItems)
                canSelect && writableOk
            }
            is PickerConfig.Selection.FileMulti -> selectedItems.isNotEmpty()
            is PickerConfig.Selection.FileSingle -> false // Instant selection, no confirm needed
        }
    }

    /**
     * Computes which items should be disabled (greyed out) based on picker constraints.
     */
    fun computeDisabledItems(
        items: List<ExplorerItem>,
        config: PickerConfig?,
    ): Set<ExplorerItem> {
        val selection = config?.selection ?: return emptySet()
        return items.filter { selection.isDisabled(it) }.toSet()
    }

    /**
     * Filters items to only those selectable in current picker mode.
     */
    fun filterSelectableItems(
        items: List<ExplorerItem>,
        config: PickerConfig?,
    ): Set<ExplorerItem> {
        return items
            .filter { it.isSelectable() && (config?.selection?.isSelectable(it) ?: true) }
            .toSet()
    }

    /**
     * Filters actions to only those allowed in picker mode.
     */
    fun filterActionsForPicker(
        actions: List<ExplorerActionBarItem>,
        config: PickerConfig?,
    ): List<ExplorerActionBarItem> {
        if (config == null) return actions
        return actions.filter { isActionAllowedInPicker(it, config) }
    }

    /**
     * Whether a surface may offer the actions that hand a file to another workspace: Open,
     * Open with and Open in editor. A picker is a modal whose caller is blocked waiting for a
     * result, so it must never spawn a tab. Both the action bar (via [filterActionsForPicker])
     * and the file options sheet ask this, so the suppression stays in one place.
     */
    fun allowsFileOpenActions(config: PickerConfig?): Boolean = config == null

    /**
     * Whether a surface may offer network location management: adding, editing, renaming or
     * removing one. A picker hands a location back to its caller, it does not administer them. Both
     * the action bar (via [filterActionsForPicker]) and the empty Network view ask this.
     */
    fun allowsNetworkManagementActions(config: PickerConfig?): Boolean = config == null

    /**
     * Extracts selected paths for picker result based on selection mode.
     */
    fun extractSelectedPaths(
        config: PickerConfig,
        currentLocation: ExplorerLocation?,
        selectedItems: Set<ExplorerItem>,
    ): List<APath<*>> {
        return when (config.selection) {
            is PickerConfig.Selection.DirectorySingle,
            is PickerConfig.Selection.SaveAs -> {
                if (selectedItems.isNotEmpty()) {
                    selectedItems.mapNotNull { extractPath(it) }
                } else {
                    val dir = currentLocation as? ExplorerLocation.Directory
                    if (dir != null) listOf(dir.path) else emptyList()
                }
            }
            is PickerConfig.Selection.DirectoryMulti -> {
                if (selectedItems.isEmpty()) {
                    val dir = currentLocation as? ExplorerLocation.Directory
                    if (dir != null) listOf(dir.path) else emptyList()
                } else {
                    selectedItems
                        .filter { it is ExplorerItem.Directory || it is ExplorerItem.Storage }
                        .mapNotNull { extractPath(it) }
                }
            }
            is PickerConfig.Selection.FileSingle -> {
                emptyList() // FileSingle uses instant selection
            }
            is PickerConfig.Selection.FileMulti -> {
                selectedItems
                    .filterIsInstance<ExplorerItem.Lookup>()
                    .filter { it is ExplorerItem.File }
                    .map { it.lookup.lookedUp }
            }
            is PickerConfig.Selection.MixedMulti -> {
                if (selectedItems.isEmpty()) {
                    val dir = currentLocation as? ExplorerLocation.Directory
                    if (dir != null) listOf(dir.path) else emptyList()
                } else {
                    selectedItems.mapNotNull { extractPath(it) }
                }
            }
        }
    }

    /**
     * A single-target picker confirms either the folder it stands in, or a storage picked at a
     * storage overview. On the Network overview only a location Butler can actually open counts.
     */
    private fun isAtConfirmableTarget(
        currentLocation: ExplorerLocation?,
        selectedItems: Set<ExplorerItem>,
    ): Boolean = when (currentLocation) {
        is ExplorerLocation.Directory -> true
        is ExplorerLocation.Device -> selectedItems.isNotEmpty()
        is ExplorerLocation.Network -> selectedItems.isNotEmpty() && selectedItems.all {
            it is ExplorerItem.Storage.Network && it.status == ExplorerItem.Storage.Network.Status.AVAILABLE
        }

        else -> false
    }

    private fun isWritable(
        currentLocation: ExplorerLocation?,
        selectedItems: Set<ExplorerItem>,
    ): Boolean {
        if (selectedItems.isNotEmpty()) {
            return selectedItems.all { item ->
                when (item) {
                    is ExplorerItem.Lookup -> item.canWrite != false
                    is ExplorerItem.Storage -> item.canWrite != false
                    else -> true
                }
            }
        }
        val directoryInfo = (currentLocation as? ExplorerLocation.Directory)?.info
        return directoryInfo?.isWritable != false
    }

    private fun extractPath(item: ExplorerItem): APath<*>? = when (item) {
        is ExplorerItem.Directory -> item.lookup.lookedUp
        is ExplorerItem.Storage -> item.target.path
        is ExplorerItem.File -> item.lookup.lookedUp
        else -> null
    }

    private fun isActionAllowedInPicker(action: ExplorerActionBarItem, config: PickerConfig): Boolean {
        return when (action) {
            // Bulk selection only makes sense when the picker accepts more than one item
            is ExplorerActionBarItem.Directory.SelectAll,
            is ExplorerActionBarItem.Trash.SelectAll,
            is ExplorerActionBarItem.TrashNested.SelectAll -> config.selection.isMultiSelect

            // Allowed: browsing, creation, and selection actions
            is ExplorerActionBarItem.Common.Refresh,
            is ExplorerActionBarItem.Common.Sort,
            is ExplorerActionBarItem.Common.Filter,
            is ExplorerActionBarItem.Common.UpdateViewStyle,
            is ExplorerActionBarItem.Directory.Create,
            is ExplorerActionBarItem.Directory.DeselectAll -> true

            // Handing a file to another workspace, also rendered by the file options sheet
            is ExplorerActionBarItem.File.Open,
            is ExplorerActionBarItem.File.OpenInTab,
            is ExplorerActionBarItem.File.OpenInEditor,
            is ExplorerActionBarItem.File.OpenWith -> allowsFileOpenActions(config)

            // Administering network locations, also offered by the empty Network view
            is ExplorerActionBarItem.Network.AddLocation,
            is ExplorerActionBarItem.Network.EditLocation,
            is ExplorerActionBarItem.Network.RemoveLocation,
            is ExplorerActionBarItem.Network.RenameLocation -> allowsNetworkManagementActions(config)

            // Blocked: modification, clipboard, device, file, and recycle bin actions
            is ExplorerActionBarItem.Directory.Copy,
            is ExplorerActionBarItem.Directory.Cut,
            is ExplorerActionBarItem.Directory.Delete,
            is ExplorerActionBarItem.Directory.Share,
            is ExplorerActionBarItem.Directory.Rename,
            is ExplorerActionBarItem.Directory.OpenInNewTabs,
            is ExplorerActionBarItem.Common.Info,
            is ExplorerActionBarItem.Common.Rename,
            is ExplorerActionBarItem.Device.AddLocation,
            is ExplorerActionBarItem.Device.RemoveLocation,
            is ExplorerActionBarItem.Device.RenameLocation,
            is ExplorerActionBarItem.File.Share,
            is ExplorerActionBarItem.File.Copy,
            is ExplorerActionBarItem.File.Cut,
            is ExplorerActionBarItem.File.Delete,
            is ExplorerActionBarItem.File.ShowProperties,
            is ExplorerActionBarItem.File.Extract,
            is ExplorerActionBarItem.Directory.Compress,
            is ExplorerActionBarItem.Directory.Extract,
            is ExplorerActionBarItem.Trash.Restore,
            is ExplorerActionBarItem.Trash.DeletePermanently,
            is ExplorerActionBarItem.Trash.EmptyBin,
            is ExplorerActionBarItem.TrashNested.Restore,
            is ExplorerActionBarItem.TrashNested.DeletePermanently,
            // Favorites are not relevant inside a picker workspace.
            is ExplorerActionBarItem.Common.AddToFavorites,
            is ExplorerActionBarItem.Common.RemoveFromFavorites,
            is ExplorerActionBarItem.Directory.ToggleFavoriteCurrent -> false
        }
    }
}
