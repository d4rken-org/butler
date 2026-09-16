package eu.darken.butler.explorer.ui.explorer.dnd

import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace

/**
 * Builds the payload for a drag started on [pressed]. Pure on purpose: the page evaluates it from
 * the state it already collected, so a multi-item drag can't collapse to a single item while a
 * selection update is still in flight through the ViewModel.
 */
object ExplorerDragPayloadFactory {

    fun build(
        state: ExplorerWorkspaceViewModel.State,
        workspaceId: Workspace.Id,
        pressed: ExplorerItem,
    ): WorkspaceDragPayload? {
        if (state.pickerConfig != null) return null
        if (pressed !is ExplorerItem.Lookup) return null
        val location = state.currentLocation
        if (location !is ExplorerLocation.Directory && location !is ExplorerLocation.Recent) return null

        val items = (state.selectionState.selectedItems.filterIsInstance<ExplorerItem.Lookup>() + pressed)
            .distinctBy { it.id }

        // Mirrors the Cut/Move gating in DirectoryActionProvider: archive content is read-only and a
        // move also has to delete from the source directory. Recent is an index rather than a
        // directory to move out of and has no writability of its own, so it drags copy-only.
        val allowMove = location is ExplorerLocation.Directory &&
            location.path !is ArchivePath &&
            items.none { it.path is ArchivePath } &&
            location.info?.isWritable == true

        return WorkspaceDragPayload(
            sourceWorkspaceId = workspaceId,
            items = items.map { WorkspaceDragPayload.Item(path = it.path, kind = it.dragKind()) },
            allowMove = allowMove,
        )
    }

    private fun ExplorerItem.Lookup.dragKind(): WorkspaceDragPayload.Kind = when {
        lookup.isDirectory -> WorkspaceDragPayload.Kind.DIRECTORY
        else -> {
            val isText = when (this) {
                is ExplorerItem.File -> TextFileDetector.isTextFile(mimeType)
                else -> TextFileDetector.isTextFile(lookup.lookedUp)
            }
            if (isText) WorkspaceDragPayload.Kind.FILE_TEXT else WorkspaceDragPayload.Kind.FILE_OTHER
        }
    }
}
