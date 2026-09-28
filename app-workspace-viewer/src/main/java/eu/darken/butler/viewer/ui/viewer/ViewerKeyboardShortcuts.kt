package eu.darken.butler.viewer.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.keyboard.KeyboardShortcut
import eu.darken.butler.common.keyboard.keyboardShortcuts
import eu.darken.butler.viewer.core.ViewerContent

/**
 * Keyboard shortcuts for the viewer page:
 * - Left / Right: previous / next file of the listing the viewer was opened from
 * - Delete: ask to delete the file; Shift+Delete asks with "Delete permanently" already ticked
 * - PageUp / PageDown: previous / next page of a PDF
 *
 * Nothing is registered unless [active]: `keyboardShortcuts` dispatches regardless of its `enabled`
 * flag, and focus traversal can still reach an inactive page, which must not answer keys then
 * (pinned by `ViewerKeyboardShortcutsTest`).
 */
@Composable
internal fun Modifier.viewerKeyboardShortcuts(
    active: Boolean,
    state: ViewerWorkspaceViewModel.State,
    onAction: (ViewerActionBarItem) -> Unit,
    onPageAction: (ViewerPageAction) -> Unit,
    onPdfPreviousPage: () -> Unit,
    onPdfNextPage: () -> Unit,
): Modifier = keyboardShortcuts(enabled = active) {
    val ready = state as? ViewerWorkspaceViewModel.State.Ready
    if (!active || ready == null) return@keyboardShortcuts
    val actions = ready.actions

    on(KeyboardShortcut.ArrowLeft) {
        actions.filterIsInstance<ViewerActionBarItem.PreviousFile>()
            .firstOrNull { it.isEnabled }
            ?.let(onAction)
    }
    on(KeyboardShortcut.ArrowRight) {
        actions.filterIsInstance<ViewerActionBarItem.NextFile>()
            .firstOrNull { it.isEnabled }
            ?.let(onAction)
    }
    on(KeyboardShortcut.Delete) {
        actions.filterIsInstance<ViewerActionBarItem.Delete>()
            .firstOrNull()
            ?.let(onAction)
    }
    on(KeyboardShortcut.ShiftDelete) {
        if (actions.any { it is ViewerActionBarItem.Delete }) {
            onPageAction(ViewerPageAction.RequestPermanentDelete)
        }
    }
    on(KeyboardShortcut.PageUp) {
        if (ready.content is ViewerContent.PdfPreview) onPdfPreviousPage()
    }
    on(KeyboardShortcut.PageDown) {
        if (ready.content is ViewerContent.PdfPreview) onPdfNextPage()
    }
}
