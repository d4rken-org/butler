package eu.darken.butler.editor.ui.editor

import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBarAction

/**
 * Exhaustiveness guarantees every bar action is handled, not that it is handled correctly: swapping
 * two same-shaped branches compiles cleanly, so the mapping is pinned by a test instead.
 */
internal fun ClipboardBarAction.toPageAction(): EditorPageAction = when (this) {
    is ClipboardBarAction.Paste -> EditorPageAction.Clipboard.Paste(clip)
    is ClipboardBarAction.Remove -> EditorPageAction.Clipboard.Remove(clip)
    is ClipboardBarAction.ShowInfo -> EditorPageAction.Clipboard.ShowInfo(clip)
    ClipboardBarAction.ClearAll -> EditorPageAction.Clipboard.Clear
}
