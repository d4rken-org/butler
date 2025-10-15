package eu.darken.butler.explorer.ui.picker

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
     * What the user is selecting
     */
    val pickerMode: PickerMode = PickerMode.DIRECTORY,

    /**
     * Allow selecting multiple items (only for FILE mode)
     */
    val allowMultiSelect: Boolean = false,

    /**
     * Workspace ID that expects the result
     * Used for routing PickerResult events
     */
    override val callerWorkspaceId: Workspace.Id? = null,
) : Workspace.ArgumentsForResult {
    @IgnoredOnParcel
    override val type: Workspace.Type = Workspace.Type.EXPLORER
}

enum class PickerMode {
    /**
     * User selects a folder (current directory)
     * allowMultiSelect is ignored (always single)
     */
    DIRECTORY,

    /**
     * User selects file(s) from current directory
     * Supports multi-select via allowMultiSelect
     */
    FILE,
}
