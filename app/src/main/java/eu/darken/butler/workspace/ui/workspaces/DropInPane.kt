package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.Workspace

/** Routes a dragged item through the same classification tapping it would use. */
fun WorkspaceDragPayload.Item.toOpenInNewTabsItem(): OpenInNewTabsUseCase.Item = when (kind) {
    WorkspaceDragPayload.Kind.DIRECTORY -> OpenInNewTabsUseCase.Item.Directory(path)
    WorkspaceDragPayload.Kind.FILE_TEXT -> OpenInNewTabsUseCase.Item.File(path, isText = true)
    WorkspaceDragPayload.Kind.FILE_OTHER -> OpenInNewTabsUseCase.Item.File(path, isText = false)
}

/**
 * Pane assignment after a drop: [workspaceId] takes [paneIndex] and leaves whatever pane it
 * occupied before, so an already open workspace is never shown twice.
 */
fun paneAssignmentAfterDrop(
    current: Map<Int, Workspace.Id>,
    paneIndex: Int,
    workspaceId: Workspace.Id,
): Map<Int, Workspace.Id> = current.filterValues { it != workspaceId } + (paneIndex to workspaceId)
