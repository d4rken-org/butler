package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.sizes.ScanProblem
import eu.darken.butler.explorer.core.sizes.AndroidDataEstimate
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface ExplorerDialogState {

    data object None : ExplorerDialogState

    data object CreateItem : ExplorerDialogState

    data class DeleteConfirmation(
        val items: Set<APath<*>>,
        val initialPermanentDelete: Boolean = false,
    ) : ExplorerDialogState

    data class RemoveLocationConfirmation(val items: List<ExplorerItem.Storage>) : ExplorerDialogState

    /**
     * The selection as it was when "Open" was tapped, in display order. The chosen mode opens this
     * snapshot, not the live selection. [viewerPaths] is empty unless [viewerModesAvailable].
     */
    data class OpenSelection(
        val items: List<ExplorerItem>,
        val viewerPaths: List<APath<*>>,
        val viewerModesAvailable: Boolean,
    ) : ExplorerDialogState

    data class LocationStorageName(
        val locationId: String,
        val currentName: String?,
    ) : ExplorerDialogState

    /**
     * Add or edit a network location. [isTesting] and [error] are driven by the view model while the
     * sheet stays open, the entered fields live in the sheet itself.
     */
    data class SmbLocationForm(
        val existing: SmbLocation? = null,
        val isTesting: Boolean = false,
        val error: CaString? = null,
    ) : ExplorerDialogState

    /** "Add network storage" asks for the protocol first, each has its own form. */
    data object NetworkProtocolChooser : ExplorerDialogState

    /**
     * Add, edit or sign in to an SFTP server. Like [SmbLocationForm], the entered fields live with
     * the sheet; a picked key file stays with the view model and only its name is shown here.
     *
     * [formId] identifies this opening of the form: a key file or a confirmation that arrives after
     * the form was closed and opened again belongs to the old one.
     */
    data class SftpLocationForm(
        val mode: SftpFormMode = SftpFormMode.ADD,
        val existing: SftpLocation? = null,
        val isTesting: Boolean = false,
        val error: CaString? = null,
        val keyFileName: String? = null,
        val isReadingKey: Boolean = false,
        val hostKeyConfirmation: SftpHostKeyConfirmation? = null,
        val retrustConfirmation: SftpRetrustConfirmation? = null,
        val formId: Uuid = Uuid.random(),
    ) : ExplorerDialogState

    data class Rename(val item: APath<*>) : ExplorerDialogState

    /** Names the favorite entry for [path]; the folder itself is not renamed. */
    data class FavoriteRename(
        val path: APath<*>,
        val currentLabel: String?,
    ) : ExplorerDialogState

    /**
     * [hasTabDefault] is kept apart from [tabRuleCount]: a tab that only carries a default has zero
     * overridden folders but is still overridden, and must still offer to clear that.
     */
    data class EditSortOptions(
        val currentSortSettings: SortSettings,
        /** Home, Device and Trash have no path, so scope and the tab checkbox do not apply there. */
        val isDirectory: Boolean = false,
        val scope: SortScope = SortScope.ALL_FOLDERS,
        val onlyThisTab: Boolean = false,
        val canUseDefaultHere: Boolean = false,
        /** Folder whose rule is in effect here, when it is not this one. */
        val inheritedFrom: CaString? = null,
        /** Nearest rule this folder's own rule or marker hides. */
        val suppressedAncestor: CaString? = null,
        val hasTabDefault: Boolean = false,
        val tabRuleCount: Int = 0,
    ) : ExplorerDialogState

    /**
     * Payload-free: the sheet renders from the page's live view style, so a control change is
     * reflected by the controls themselves and not just by the listing behind them.
     */
    data object EditViewStyle : ExplorerDialogState

    /**
     * What a "Calculate sizes" run produced for the folder that is open, and what can be done with
     * it. [root] is the scan's own root, which may be an ancestor of that folder.
     */
    data class CalculatedSizes(
        val root: APath<*>,
        val scannedAt: Instant,
        val directoryCount: Int,
        val errorCount: Int,
        /** Capped by the scan, so shorter than [errorCount] on a folder with many unreadable spots. */
        val problems: List<ScanProblem> = emptyList(),
        val estimate: AndroidDataEstimate? = null,
        val estimateFailure: AndroidDataEstimate.Failure? = null,
    ) : ExplorerDialogState

    data class FilterOptions(
        val includePattern: String,
        val excludePattern: String,
        val fileTypeFilter: FileTypeFilter,
        val useRegexPatterns: Boolean,
    ) : ExplorerDialogState

    /**
     * [showInFolder] is decided when the sheet opens: only a listing that is not the file's own
     * folder - Recent - has somewhere to send the user.
     */
    data class FileOptions(
        val item: ExplorerItem.File,
        val showInFolder: Boolean = false,
    ) : ExplorerDialogState

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

    /** Items dropped onto a folder or pane, waiting for the user to pick copy or move. */
    data class DropConfirmation(
        val payload: WorkspaceDragPayload,
        val destination: APath<*>,
    ) : ExplorerDialogState

    /** Items dropped from another workspace onto the Trash view, waiting to confirm the move to trash. */
    data class TrashDropConfirmation(
        val payload: WorkspaceDragPayload,
    ) : ExplorerDialogState

    data class ClipboardInfo(val clip: ClipboardClip) : ExplorerDialogState

    data class CreateFileFromText(val clip: ClipboardClip.Text) : ExplorerDialogState

    data class ItemInfo(val context: InfoContext) : ExplorerDialogState {
        sealed interface InfoContext {
            data class SingleFile(val item: ExplorerItem.File) : InfoContext
            data class SingleDirectory(val item: ExplorerItem.Directory) : InfoContext
            data class SingleSAF(val item: ExplorerItem.Storage.SAF) : InfoContext
            data class SingleLocalStorage(val item: ExplorerItem.Storage.Local) : InfoContext

            /**
             * Only the location is remembered, [item] is filled in from the listing every time the
             * UI state is built: a sheet opened while the address was still being looked up has to
             * show the answer when it arrives, not the row it was opened from.
             */
            data class SingleNetwork(
                val locationId: Uuid,
                val item: ExplorerItem.Storage.Network? = null,
                val revealed: RevealedPassword? = null,
                val isRevealing: Boolean = false,
                val capacity: Capacity? = null,
                /**
                 * Identifies this opening of the sheet rather than the location it describes:
                 * dismissing and reopening the same share are two sheets, and work started for the
                 * first one must not land on the second.
                 */
                val sheetInstanceId: Uuid = Uuid.random(),
            ) : InfoContext {

                /**
                 * How full the share is. Null means it was never asked for, because there is
                 * nothing to sign in with or nothing to reach.
                 */
                sealed interface Capacity {
                    data object Loading : Capacity
                    data object Unavailable : Capacity
                    data class Data(val totalBytes: Long, val freeBytes: Long) : Capacity
                }
            }

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

enum class NetworkProtocol {
    SMB,
    SFTP,
}

enum class SftpFormMode {
    ADD,
    EDIT,

    /** Opened because the stored credential was rejected or is gone: a fresh secret is required. */
    SIGN_IN,
}

/**
 * A server key presented to a connection test, waiting for the user's decision.
 *
 * Accepting it only counts for exactly this endpoint, key and form: [formRevision] and
 * [keyGeneration] are the field and key-file state the test ran with.
 */
data class SftpHostKeyConfirmation(
    val host: String,
    val port: Int,
    val presentedKey: TrustedHostKey,
    val formRevision: Int,
    val keyGeneration: Int,
    val id: Uuid = Uuid.random(),
)

/**
 * A changed server key, taken from the failed connection as it was: nothing is re-read or parsed.
 *
 * [fromFormTest] marks one found by the form's own connection test: accepting it keeps the form open,
 * to be submitted again against the new pin.
 */
data class SftpRetrustConfirmation(
    val locationId: Uuid,
    val host: String,
    val port: Int,
    val endpoint: String,
    val storedKey: TrustedHostKey,
    val presentedKey: TrustedHostKey,
    val trustRevision: Int,
    val fromFormTest: Boolean = false,
    val id: Uuid = Uuid.random(),
)

/**
 * A stored password on its way to the screen.
 *
 * Deliberately neither a data class nor a bare String: the dialog states are data classes whose
 * generated `toString()` is reachable from logging, and a plaintext password must never be able to
 * arrive in a log line that way.
 */
class RevealedPassword(val value: String) {
    override fun toString(): String = "RevealedPassword(***)"
}
