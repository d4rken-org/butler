package eu.darken.butler.explorer.ui.explorer.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import eu.darken.butler.common.keyboard.KeyboardShortcut
import eu.darken.butler.common.keyboard.keyboardShortcuts
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

/**
 * Keyboard shortcuts for Explorer workspace.
 *
 * Supported shortcuts:
 * - Copy (Ctrl+C): Copy selected items to clipboard
 * - Cut (Ctrl+X): Cut selected items to clipboard
 * - Paste (Ctrl+V): Paste from clipboard
 * - SelectAll (Ctrl+A): Select all items
 * - New (Ctrl+N): Create new file/directory
 * - Delete (Delete): Delete selected items
 * - Escape: Clear focus and selection
 * - F2: Rename focused/selected item
 * - Enter: Open/navigate to focused/selected item
 * - Backspace: Go to parent directory
 * - Arrow Up/Down: Navigate focus (list view)
 * - Arrow Left/Right: Navigate focus (grid view)
 * - Home: Focus first item
 * - End: Focus last item
 */
fun Modifier.explorerKeyboardShortcuts(
    availableActions: List<ExplorerAction>,
    clipboardEntries: List<ClipboardClip>,
    selectedItems: Set<ExplorerItem>,
    focusedItem: ExplorerItem?,
    viewStyle: ExplorerViewStyle,
    gridColumns: Int,
    enabled: Boolean = true,
    onExecuteAction: (ExplorerAction) -> Unit,
    onPaste: (ClipboardClip) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onClearFocus: () -> Unit,
    onNavigateToItem: (ExplorerItem) -> Unit,
    onGoBack: () -> Unit,
    onMoveFocusUp: () -> Unit,
    onMoveFocusDown: () -> Unit,
    onMoveFocusLeft: () -> Unit,
    onMoveFocusRight: () -> Unit,
    onMoveFocusToFirst: () -> Unit,
    onMoveFocusToLast: () -> Unit,
    onActivateFocusedItem: () -> Unit,
    onRenameFocusedItem: () -> Unit,
): Modifier = composed {
    keyboardShortcuts(enabled = enabled) {
        on(KeyboardShortcut.Copy) {
            val copyAction = availableActions
                .filterIsInstance<ExplorerAction.Directory.Copy>()
                .firstOrNull()
            if (copyAction != null && copyAction.isEnabled) {
                onExecuteAction(copyAction)
            }
        }
        on(KeyboardShortcut.Cut) {
            val cutAction = availableActions
                .filterIsInstance<ExplorerAction.Directory.Cut>()
                .firstOrNull()
            if (cutAction != null && cutAction.isEnabled) {
                onExecuteAction(cutAction)
            }
        }
        on(KeyboardShortcut.Paste) {
            clipboardEntries.firstOrNull()?.let { clip -> onPaste(clip) }
        }
        on(KeyboardShortcut.SelectAll) {
            onSelectAll()
        }
        on(KeyboardShortcut.New) {
            val createAction = availableActions
                .filterIsInstance<ExplorerAction.Directory.Create>()
                .firstOrNull()
            if (createAction != null && createAction.isEnabled) {
                onExecuteAction(createAction)
            }
        }
        on(KeyboardShortcut.Delete) {
            val deleteAction = availableActions
                .filterIsInstance<ExplorerAction.Directory.Delete>()
                .firstOrNull()
            if (deleteAction != null && deleteAction.isEnabled) {
                onExecuteAction(deleteAction)
            }
        }
        on(KeyboardShortcut.Escape) {
            when {
                focusedItem != null -> onClearFocus()
                selectedItems.isNotEmpty() -> onClearSelection()
            }
        }
        on(KeyboardShortcut.F2) {
            val renameAction = availableActions
                .filterIsInstance<ExplorerAction.Directory.Rename>()
                .firstOrNull()
            when {
                // If Rename action is available (selection mode), use it
                renameAction != null && renameAction.isEnabled -> onExecuteAction(renameAction)
                // If there's a focused item, rename it directly
                focusedItem != null -> onRenameFocusedItem()
            }
        }
        on(KeyboardShortcut.Enter) {
            when {
                // If there's a single selected item, navigate to it
                selectedItems.size == 1 -> onNavigateToItem(selectedItems.single())
                // If there's a focused item (no selection), activate it
                focusedItem != null -> onActivateFocusedItem()
            }
        }
        on(KeyboardShortcut.Backspace) {
            onGoBack()
        }

        // Arrow key navigation
        on(KeyboardShortcut.ArrowUp) {
            when (viewStyle) {
                is ExplorerViewStyle.List -> onMoveFocusUp()
                is ExplorerViewStyle.Grid -> onMoveFocusLeft()
            }
        }
        on(KeyboardShortcut.ArrowDown) {
            when (viewStyle) {
                is ExplorerViewStyle.List -> onMoveFocusDown()
                is ExplorerViewStyle.Grid -> onMoveFocusRight()
            }
        }
        on(KeyboardShortcut.ArrowLeft) {
            if (viewStyle is ExplorerViewStyle.Grid) {
                onMoveFocusUp()
            }
        }
        on(KeyboardShortcut.ArrowRight) {
            if (viewStyle is ExplorerViewStyle.Grid) {
                onMoveFocusDown()
            }
        }
        on(KeyboardShortcut.Home) {
            onMoveFocusToFirst()
        }
        on(KeyboardShortcut.End) {
            onMoveFocusToLast()
        }
    }
}
