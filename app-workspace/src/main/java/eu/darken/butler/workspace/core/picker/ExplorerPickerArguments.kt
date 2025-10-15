package eu.darken.butler.workspace.core.picker

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * Arguments for launching Explorer in picker mode
 */
@Parcelize
data class ExplorerPickerArguments(
    /**
     * Starting directory for picker
     */
    val startPath: APath<*>? = null,

    /**
     * What the user can select and how they interact with items.
     * Defines selection behavior, multi-select capability, and item filtering.
     *
     * Common modes:
     * - [PickerConfig.Selection.DirectorySingle]: Choose one folder (default)
     * - [PickerConfig.Selection.FileSingle]: Tap file to instantly select
     * - [PickerConfig.Selection.FileMulti]: Select multiple files
     * - [PickerConfig.Selection.DirectoryMulti]: Select multiple folders
     */
    val selection: PickerConfig.Selection = PickerConfig.Selection.DirectorySingle,

    /**
     * Workspace ID that expects the result
     * Used for routing PickerResult events
     */
    override val callerWorkspaceId: Workspace.Id? = null,
) : Workspace.ArgumentsForResult {
    @IgnoredOnParcel
    override val type: Workspace.Type = Workspace.Type.EXPLORER
}
