package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.items.grid.LookupItemGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.PREVIEWS_ALWAYS_SETTLED
import eu.darken.butler.explorer.ui.explorer.items.grid.PeekGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.ShortcutGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.StorageGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.TrashItemGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.TrashNestedItemGrid
import eu.darken.butler.explorer.ui.explorer.items.row.LookupItemRow
import eu.darken.butler.explorer.ui.explorer.items.row.PeekRow
import eu.darken.butler.explorer.ui.explorer.items.row.ShortcutRow
import eu.darken.butler.explorer.ui.explorer.items.row.StorageRow
import eu.darken.butler.explorer.ui.explorer.items.row.TrashItemRow
import eu.darken.butler.explorer.ui.explorer.items.row.TrashNestedItemRow
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.ui.dnd.dropZone
import eu.darken.butler.workspace.ui.dnd.rememberWorkspaceDragSource

/**
 * Unified item renderer for ExplorerWorkspacePage.
 * Handles rendering of all ExplorerItem types in both List and Grid view styles.
 */
@Composable
fun ExplorerItemRenderer(
    item: ExplorerItem,
    viewStyle: ExplorerViewStyle,
    state: ExplorerWorkspaceViewModel.State,
    isFocused: Boolean,
    onItemClick: (ExplorerItem) -> Unit,
    onItemLongClick: (ExplorerItem) -> Unit,
    onNavigate: (ExplorerItem) -> Unit,
    onToggleSelection: (ExplorerItem) -> Unit,
    dragPayloadFactory: ((ExplorerItem) -> WorkspaceDragPayload?)? = null,
    previewsSettled: State<Boolean> = PREVIEWS_ALWAYS_SETTLED,
) {
    val isSelected = state.selectionState.selectedItems.contains(item)
    val isEnabled = item !in state.disabledItems
    val showSelection = state.shouldShowSelection(item)

    val focusModifier = if (isFocused) {
        Modifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(8.dp),
        )
    } else {
        Modifier
    }

    // The long press arms the drag while the pointer is still down, which is what the platform needs
    // to pick up the drag. A null payload means no drag.
    val dragSource = dragPayloadFactory?.let { factory ->
        rememberWorkspaceDragSource(
            cornerRadius = when (viewStyle) {
                is ExplorerViewStyle.List -> 8.dp
                is ExplorerViewStyle.Grid -> 4.dp
            },
        ) { factory(item) }
    }

    Box(
        modifier = focusModifier
            .dropZone(key = item.id, destination = item.dropDestination())
            .then(dragSource?.modifier ?: Modifier),
    ) {
        ItemContent(
            item = item,
            viewStyle = viewStyle,
            state = state,
            isSelected = isSelected,
            isEnabled = isEnabled,
            showSelection = showSelection,
            onItemClick = onItemClick,
            onItemLongClick = {
                // The cross-pane drag starts only from an already selected item; long-pressing an
                // unselected item in selection mode extends the selection instead.
                if (state.selectionState.isSelectionMode && it in state.selectionState.selectedItems) {
                    dragSource?.startDrag()
                }
                onItemLongClick(it)
            },
            onNavigate = onNavigate,
            onToggleSelection = onToggleSelection,
            previewsSettled = previewsSettled,
        )
    }
}

/** Folders take drops; a read-only one or archive content has nothing to offer a drop. */
private fun ExplorerItem.dropDestination(): APath<*>? = when {
    this !is ExplorerItem.Directory -> null
    canWrite == false -> null
    path is ArchivePath -> null
    else -> path
}

@Composable
private fun ItemContent(
    item: ExplorerItem,
    viewStyle: ExplorerViewStyle,
    state: ExplorerWorkspaceViewModel.State,
    isSelected: Boolean,
    isEnabled: Boolean,
    showSelection: Boolean,
    onItemClick: (ExplorerItem) -> Unit,
    onItemLongClick: (ExplorerItem) -> Unit,
    onNavigate: (ExplorerItem) -> Unit,
    onToggleSelection: (ExplorerItem) -> Unit,
    previewsSettled: State<Boolean>,
) {
    when (item) {
        is ExplorerItem.Lookup -> {
            val isHighlighted = item.id in state.highlightedItemIds
            val decorations = decorationsFor(item, state)
            when (viewStyle) {
                is ExplorerViewStyle.List -> LookupItemRow(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    showSelection = showSelection,
                    isEnabled = isEnabled,
                    isHighlighted = isHighlighted,
                    decorations = decorations,
                )
                is ExplorerViewStyle.Grid -> LookupItemGrid(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    showSelection = showSelection,
                    isEnabled = isEnabled,
                    isHighlighted = isHighlighted,
                    decorations = decorations,
                    previewsSettled = previewsSettled,
                )
            }
        }

        is ExplorerItem.Peek -> {
            when (viewStyle) {
                is ExplorerViewStyle.List -> PeekRow(item = item)
                is ExplorerViewStyle.Grid -> PeekGrid(item = item)
            }
        }

        is ExplorerItem.Shortcut -> {
            when (viewStyle) {
                is ExplorerViewStyle.List -> ShortcutRow(
                    item = item,
                    isEnabled = isEnabled,
                    onClick = { onNavigate(item) },
                )
                is ExplorerViewStyle.Grid -> ShortcutGrid(
                    item = item,
                    isEnabled = isEnabled,
                    onClick = { onNavigate(item) },
                )
            }
        }

        is ExplorerItem.Storage -> {
            // Storage items have custom showSelection logic - based on selectableItems membership
            val storageShowSelection = state.selectionState.selectedItems.isNotEmpty() &&
                item in state.selectionState.selectableItems
            val decorations = decorationsFor(item, state)
            // Same entry point as every other item type: it guards against a live selection instead
            // of the composed one, so it can't race the drag session's own selection update.
            val onLongClick = { onItemLongClick(item) }
            when (viewStyle) {
                is ExplorerViewStyle.List -> StorageRow(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = onLongClick,
                    showSelection = storageShowSelection,
                    isEnabled = isEnabled,
                    decorations = decorations,
                )
                is ExplorerViewStyle.Grid -> StorageGrid(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = onLongClick,
                    showSelection = storageShowSelection,
                    isEnabled = isEnabled,
                    decorations = decorations,
                )
            }
        }

        is ExplorerItem.Trash.Root -> {
            when (viewStyle) {
                is ExplorerViewStyle.List -> TrashItemRow(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    showSelection = showSelection,
                )
                is ExplorerViewStyle.Grid -> TrashItemGrid(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    showSelection = showSelection,
                )
            }
        }

        is ExplorerItem.Trash.Nested -> {
            when (viewStyle) {
                is ExplorerViewStyle.List -> TrashNestedItemRow(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    showSelection = showSelection,
                )
                is ExplorerViewStyle.Grid -> TrashNestedItemGrid(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    showSelection = showSelection,
                )
            }
        }
    }
}

/**
 * Derives the [ItemDecorations] to overlay on [item]'s leading icon from the current
 * workspace [state]. Single point of derivation — adding a future decoration is one
 * `copy(...)` here plus a render branch in [LeadingIconSlot] plus a field in
 * [ItemDecorations]. Returns the no-op default for items that aren't decoratable.
 */
private fun decorationsFor(
    item: ExplorerItem,
    state: ExplorerWorkspaceViewModel.State,
): ItemDecorations = when (item) {
    is ExplorerItem.Lookup -> ItemDecorations(
        isFavorite = state.favoritePaths.any { it.matches(item.lookup.lookedUp) },
    )
    is ExplorerItem.Storage -> ItemDecorations(
        isFavorite = state.favoritePaths.any { it.matches(item.target.path) },
    )
    else -> ItemDecorations()
}

