package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

sealed interface ExplorerDialogState {

    data object None : ExplorerDialogState

    data object CreateItem : ExplorerDialogState

    data class DeleteConfirmation(val items: Set<APath<*>>) : ExplorerDialogState

    data class RemoveLocationConfirmation(val items: List<ExplorerItem.Storage.SAF>) : ExplorerDialogState

    data class LocationStorageName(
        val locationId: String,
        val currentName: String?,
    ) : ExplorerDialogState

    data class Rename(val item: APath<*>) : ExplorerDialogState

    data class EditSortOptions(val currentSortSettings: SortSettings) : ExplorerDialogState

    data class FilterOptions(
        val includePattern: String,
        val excludePattern: String,
        val fileTypeFilter: FileTypeFilter,
        val useRegexPatterns: Boolean,
    ) : ExplorerDialogState

    data class FileOptions(val item: ExplorerItem.File) : ExplorerDialogState

    data class RecycleBinItemOptions(val item: ExplorerItem.RecycleBinItem) : ExplorerDialogState

    data object EmptyRecycleBinConfirmation : ExplorerDialogState

    data class ClipboardInfo(val clip: ClipboardClip) : ExplorerDialogState

    data class ItemInfo(val context: InfoContext) : ExplorerDialogState {
        sealed interface InfoContext {
            data class SingleFile(val item: ExplorerItem.File) : InfoContext
            data class SingleDirectory(val item: ExplorerItem.Directory) : InfoContext
            data class SingleSAF(val item: ExplorerItem.Storage.SAF) : InfoContext
            data class MultipleItems(
                val selectedItems: List<ExplorerItem>,
                val fileCount: Int,
                val directoryCount: Int,
                val totalSize: Long?,
            ) : InfoContext
            data class DeviceView(val location: eu.darken.butler.explorer.core.engine.ExplorerLocation.Device) : InfoContext
            data class HomeView(val location: eu.darken.butler.explorer.core.engine.ExplorerLocation.Home) : InfoContext
        }
    }
}