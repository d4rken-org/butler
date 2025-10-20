package eu.darken.butler.searcher.ui.search.dialogs

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

sealed interface SearcherDialogState {
    data object None : SearcherDialogState
    data class DeleteConfirmation(val paths: Set<APath<*>>) : SearcherDialogState
    data class FileInfo(val result: SearchResult) : SearcherDialogState
    data class ClipboardInfo(val clip: ClipboardClip) : SearcherDialogState
}
