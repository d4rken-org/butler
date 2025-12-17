package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.runtime.Composable
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.items.grid.LookupItemGrid
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

/**
 * Unified item renderer for ExplorerWorkspacePage.
 * Handles rendering of all ExplorerItem types in both List and Grid view styles.
 */
@Composable
fun ExplorerItemRenderer(
    item: ExplorerItem,
    viewStyle: ExplorerViewStyle,
    state: ExplorerWorkspaceViewModel.State,
    onItemClick: (ExplorerItem) -> Unit,
    onItemLongClick: (ExplorerItem) -> Unit,
    onNavigate: (ExplorerItem) -> Unit,
    onToggleSelection: (ExplorerItem) -> Unit,
) {
    val isSelected = state.selectionState.selectedItems.contains(item)
    val isEnabled = item !in state.disabledItems
    val showSelection = state.shouldShowSelection(item)

    when (item) {
        is ExplorerItem.Lookup -> {
            val isHighlighted = item.id in state.highlightedItemIds
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
            when (viewStyle) {
                is ExplorerViewStyle.List -> StorageRow(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = { onToggleSelection(item) },
                    showSelection = storageShowSelection,
                    isEnabled = isEnabled,
                )
                is ExplorerViewStyle.Grid -> StorageGrid(
                    item = item,
                    isSelected = isSelected,
                    onToggleSelection = { onToggleSelection(item) },
                    onClick = { onItemClick(item) },
                    onLongClick = { onToggleSelection(item) },
                    showSelection = storageShowSelection,
                    isEnabled = isEnabled,
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
