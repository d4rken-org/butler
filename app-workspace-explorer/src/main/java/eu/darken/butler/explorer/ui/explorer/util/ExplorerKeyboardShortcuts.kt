package eu.darken.butler.explorer.ui.explorer.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import eu.darken.butler.common.keyboard.KeyboardShortcut
import eu.darken.butler.common.keyboard.keyboardShortcuts
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
 * - Escape: Clear selection
 */
fun Modifier.explorerKeyboardShortcuts(
    availableActions: List<ExplorerAction>,
    clipboardEntries: List<ClipboardClip>,
    onExecuteAction: (ExplorerAction) -> Unit,
    onPaste: (ClipboardClip) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
): Modifier = composed {
    keyboardShortcuts {
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
        if (ExplorerAction.Directory.DeselectAll in availableActions) {
            onClearSelection()
        }
    }
}
}
