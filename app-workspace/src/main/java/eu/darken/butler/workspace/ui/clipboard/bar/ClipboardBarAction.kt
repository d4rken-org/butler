package eu.darken.butler.workspace.ui.clipboard.bar

import eu.darken.butler.workspace.core.clipboard.ClipboardClip

/**
 * What the user did on a [WorkspaceClipboardFloatingBar], as a single typed channel.
 *
 * The names describe the gesture on the bar, not any workspace's handling of it: the Searcher
 * answers [Paste] by opening the target in the Explorer, which is a handler decision.
 */
sealed interface ClipboardBarAction {
    data class Paste(val clip: ClipboardClip) : ClipboardBarAction
    data class Remove(val clip: ClipboardClip) : ClipboardBarAction
    data class ShowInfo(val clip: ClipboardClip) : ClipboardBarAction
    data object ClearAll : ClipboardBarAction
}
