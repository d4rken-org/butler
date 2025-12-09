package eu.darken.butler.explorer.ui.picker

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.explorer.core.picker.isDisabled
import eu.darken.butler.explorer.core.picker.isSelectable
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
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
                val atDirectory = currentLocation is ExplorerLocation.Directory
                    || (currentLocation is ExplorerLocation.Device && selectedItems.isNotEmpty())
                atDirectory && isWritable(currentLocation, selectedItems)
            }
            is PickerConfig.Selection.SaveAs -> {
                val hasValidFilename = saveAsFilename.isNotBlank()
                val atDirectory = currentLocation is ExplorerLocation.Directory
                    || (currentLocation is ExplorerLocation.Device && selectedItems.isNotEmpty())
                hasValidFilename && atDirectory && isWritable(currentLocation, selectedItems)
            }
            is PickerConfig.Selection.DirectoryMulti,
            is PickerConfig.Selection.MixedMulti -> {
                val canSelect = selectedItems.isNotEmpty() || currentLocation is ExplorerLocation.Directory
                canSelect && isWritable(currentLocation, selectedItems)
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
        actions: List<ExplorerAction>,
        config: PickerConfig?,
    ): List<ExplorerAction> {
        if (config == null) return actions
        return actions.filter { isActionAllowedInPicker(it) }
    }

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

    private fun isActionAllowedInPicker(action: ExplorerAction): Boolean {
        return when (action) {
            // Allowed: browsing, creation, and selection actions
            is ExplorerAction.Common.Refresh,
            is ExplorerAction.Common.Sort,
            is ExplorerAction.Common.Filter,
            is ExplorerAction.Common.UpdateViewStyle,
            is ExplorerAction.Directory.Create,
            is ExplorerAction.Directory.SelectAll,
            is ExplorerAction.Directory.DeselectAll,
            is ExplorerAction.Trash.SelectAll,
            is ExplorerAction.TrashNested.SelectAll -> true

            // Blocked: modification, clipboard, device, and recycle bin actions
            is ExplorerAction.Directory.Copy,
            is ExplorerAction.Directory.Cut,
            is ExplorerAction.Directory.Delete,
            is ExplorerAction.Directory.Share,
            is ExplorerAction.Directory.Rename,
            is ExplorerAction.Directory.OpenInNewTabs,
            is ExplorerAction.Common.Info,
            is ExplorerAction.Device.AddLocation,
            is ExplorerAction.Device.RemoveLocation,
            is ExplorerAction.Device.RenameLocation,
            is ExplorerAction.Trash.RestoreSelected,
            is ExplorerAction.Trash.DeletePermanentlySelected,
            is ExplorerAction.Trash.EmptyBin,
            is ExplorerAction.TrashNested.RestoreSelected,
            is ExplorerAction.TrashNested.DeletePermanentlySelected -> false
        }
    }
}
