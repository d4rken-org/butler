package eu.darken.butler.searcher.ui.search.dnd

import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.resultKey
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.searcher.ui.search.util.toOpenInNewTabsItem
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.Workspace

/**
 * Builds the payload for a drag started on [pressed]. Pure on purpose: the page evaluates it from
 * the state it already collected, so a multi-item drag can't collapse while a selection update is
 * still in flight through the ViewModel.
 */
object SearcherDragPayloadFactory {

    fun build(
        state: SearcherWorkspaceViewModel.State,
        workspaceId: Workspace.Id,
        pressed: SearchItem,
    ): WorkspaceDragPayload? {
        if (state !is SearcherWorkspaceViewModel.State.Ready) return null

        val items = (state.selectionState.selectedResults + pressed).distinctBy { it.resultKey }

        return WorkspaceDragPayload(
            sourceWorkspaceId = workspaceId,
            items = items.map { WorkspaceDragPayload.Item(path = it.path, kind = it.dragKind()) },
            // Same rule as Cut: archive entries can't be given up, everything else can.
            allowMove = items.none { it.path is ArchivePath },
        )
    }

    private fun SearchItem.dragKind(): WorkspaceDragPayload.Kind = when (val item = toOpenInNewTabsItem()) {
        is OpenInNewTabsUseCase.Item.Directory -> WorkspaceDragPayload.Kind.DIRECTORY
        is OpenInNewTabsUseCase.Item.File -> when {
            item.isText -> WorkspaceDragPayload.Kind.FILE_TEXT
            else -> WorkspaceDragPayload.Kind.FILE_OTHER
        }
    }
}
