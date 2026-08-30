package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBarAction
import eu.darken.butler.workspace.ui.operations.bar.OperationsBarAction

/**
 * Exhaustiveness guarantees every bar action is handled, not that it is handled correctly, and these
 * two are the least obvious mappings in the codebase: the Searcher cannot paste in place, and
 * `Operations.Cancel` would compile just as well as `Overlays.RequestCancelOperation`.
 */
internal fun OperationsBarAction.toPageAction(): SearcherPageAction = when (this) {
    is OperationsBarAction.RequestCancel -> SearcherPageAction.Overlays.RequestCancelOperation(id)
    is OperationsBarAction.Dismiss -> SearcherPageAction.Operations.Dismiss(id)
    is OperationsBarAction.ShowConflict -> SearcherPageAction.Operations.ShowConflict(id)
    is OperationsBarAction.ShowDetails -> SearcherPageAction.Overlays.ShowOperationDetails(id)
    OperationsBarAction.ClearCompleted -> SearcherPageAction.Operations.ClearCompleted
}

internal fun ClipboardBarAction.toPageAction(): SearcherPageAction = when (this) {
    is ClipboardBarAction.Paste -> SearcherPageAction.Clipboard.OpenInExplorer(clip)
    is ClipboardBarAction.Remove -> SearcherPageAction.Clipboard.RemoveEntry(clip)
    is ClipboardBarAction.ShowInfo -> SearcherPageAction.Clipboard.ClickEntry(clip)
    ClipboardBarAction.ClearAll -> SearcherPageAction.Clipboard.ClearAll
}
