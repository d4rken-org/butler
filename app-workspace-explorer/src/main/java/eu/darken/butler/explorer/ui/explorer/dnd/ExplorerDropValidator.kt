package eu.darken.butler.explorer.ui.explorer.dnd

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dnd.validateDropPaths

/**
 * The single gate for accepting a dropped [payload]: used when the drag session starts, when the
 * drop arrives and again before the operation is launched. Returns the destination directory or
 * null when this workspace can't take the drop.
 */
fun validateDropDestination(
    state: ExplorerWorkspaceViewModel.State,
    workspaceId: Workspace.Id,
    payload: WorkspaceDragPayload,
): APath<*>? {
    if (state.pickerConfig != null) return null
    if (payload.sourceWorkspaceId == workspaceId) return null

    val directory = state.currentLocation as? ExplorerLocation.Directory ?: return null
    if (directory.path is ArchivePath) return null
    if (directory.info?.isWritable != true) return null

    if (!validateDropPaths(payload.items, directory.path)) return null
    return directory.path
}
