package eu.darken.butler.searcher.ui.search.dialogs

import eu.darken.butler.common.files.APath

sealed interface SearcherDialogState {
    data object None : SearcherDialogState
    data class DeleteConfirmation(val paths: Set<APath<*>>) : SearcherDialogState
}
