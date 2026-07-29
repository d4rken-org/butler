package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

sealed interface ExplorerDialogState {

    data object None : ExplorerDialogState

    data object CreateItem : ExplorerDialogState

    data class DeleteConfirmation(
        val items: Set<APath<*>>,
        val forcePermDelete: Boolean = false,
    ) : ExplorerDialogState

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

    data class CompressOptions(
        val sources: Set<APath<*>>,
        val destinationDir: APath<*>,
        val suggestedName: String,
        val defaultFormat: ArchiveFormat = ArchiveFormat.ZIP,
        val defaultPreset: CompressionPreset = CompressionPreset.NORMAL,
    ) : ExplorerDialogState

    /** [pending] carries the ready-to-run command; on cancel its password must be wiped. */
    data class CompressOverwriteConfirmation(
        val pending: ExplorerCommand.Compress,
    ) : ExplorerDialogState

    data class TrashItemOptions(val item: ExplorerItem.Trash.Root) : ExplorerDialogState

    data class TrashNestedItemOptions(val item: ExplorerItem.Trash.Nested) : ExplorerDialogState

    data object EmptyTrashConfirmation : ExplorerDialogState

    /** Items dropped from another workspace, waiting for the user to pick copy or move. */
    data class DropConfirmation(
        val payload: WorkspaceDragPayload,
        val destination: APath<*>,
    ) : ExplorerDialogState

    data class ClipboardInfo(val clip: ClipboardClip) : ExplorerDialogState

    data class CreateFileFromText(val clip: ClipboardClip.Text) : ExplorerDialogState

    data class ItemInfo(val context: InfoContext) : ExplorerDialogState {
        sealed interface InfoContext {
            data class SingleFile(val item: ExplorerItem.File) : InfoContext
            data class SingleDirectory(val item: ExplorerItem.Directory) : InfoContext
            data class SingleSAF(val item: ExplorerItem.Storage.SAF) : InfoContext
            data class SingleLocalStorage(val item: ExplorerItem.Storage.Local) : InfoContext
            data class MultipleItems(
                val selectedItems: List<ExplorerItem>,
                val fileCount: Int,
                val directoryCount: Int,
                val totalSize: Long?,
            ) : InfoContext

            data class DeviceView(val location: eu.darken.butler.explorer.core.engine.ExplorerLocation.Device) :
                InfoContext

            data class HomeView(val location: eu.darken.butler.explorer.core.engine.ExplorerLocation.Home) : InfoContext
        }
    }
}