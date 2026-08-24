package eu.darken.butler.explorer.ui.explorer.dragselect

import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.needsSignIn
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload

/**
 * The items a drag may sweep over, in display order. Sourced from the picker-aware eligibility set
 * instead of a generic "is selectable" test, so e.g. a file picker can't drag-select directories.
 *
 * A picker also skips network locations that need a sign-in: the tap and long-press routes send them
 * to the sign-in form instead of selecting them, and a drag must not be the one way to get an
 * unbrowsable location into the result.
 */
fun explorerDragSelectKeys(state: ExplorerWorkspaceViewModel.State): List<String> {
    val selectable = state.selectionState.selectableItems
    val isPicking = state.pickerConfig != null
    return state.items.orEmpty()
        .filter { it in selectable && it !in state.disabledItems }
        .filterNot { isPicking && it.needsSignIn() }
        .map { it.id }
}

/**
 * Whether drag-select owns the long press on [anchorId], or a cross-pane drag does.
 *
 * The payload factory is evaluated for the pressed item rather than merely tested for presence: it
 * returns null for pickers and for items that can't be dragged at all (storage volumes, trash
 * roots), and those presses would otherwise end up owned by neither gesture.
 *
 * In selection mode an unselected anchor is always claimed by drag-select, so long-pressing it
 * extends the selection; only an already selected anchor may fall through to the cross-pane drag.
 */
fun explorerDragSelectClaims(
    state: ExplorerWorkspaceViewModel.State,
    anchorId: String,
    dragPayloadFactory: ((ExplorerItem) -> WorkspaceDragPayload?)?,
): Boolean {
    if (state.pickerConfig?.selection?.isMultiSelect == false) return false
    if (!state.selectionState.isSelectionMode) return true
    val anchor = state.items?.firstOrNull { it.id == anchorId } ?: return true
    if (anchor !in state.selectionState.selectedItems) return true
    return dragPayloadFactory?.invoke(anchor) == null
}

/**
 * Resolves dragged ids back to items. Selected items the current filter hides are kept out of the
 * listing but stay selected, so ids that don't resolve against [ExplorerWorkspaceViewModel.State.items]
 * are looked up in the live selection - resolving only against the listing would silently drop them.
 */
fun explorerDragSelectItems(
    state: ExplorerWorkspaceViewModel.State,
    ids: Set<String>,
): Set<ExplorerItem> {
    val visible = state.items.orEmpty().associateBy { it.id }
    val selected = state.selectionState.selectedItems.associateBy { it.id }
    return ids.mapNotNullTo(mutableSetOf()) { visible[it] ?: selected[it] }
}
