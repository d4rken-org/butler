package eu.darken.butler.explorer.ui.picker

import eu.darken.butler.workspace.core.Workspace

/**
 * Runtime picker configuration stored in workspace instance
 * NOT flowed through Workspace.Info (immutable after creation)
 */
data class PickerConfig(
    /**
     * Workspace ID that launched this picker
     */
    val callerWorkspaceId: Workspace.Id,

    /**
     * What the picker is selecting
     */
    val pickerMode: PickerMode,

    /**
     * Whether multi-select is enabled
     */
    val allowMultiSelect: Boolean,
)
