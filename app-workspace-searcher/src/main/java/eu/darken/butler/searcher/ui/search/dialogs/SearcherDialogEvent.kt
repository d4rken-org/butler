package eu.darken.butler.searcher.ui.search.dialogs

import eu.darken.butler.common.files.APath

sealed interface SearcherDialogEvent {
    data class ShowDeleteConfirmation(
        val paths: Set<APath<*>>,
        val forcePermDelete: Boolean = false,
    ) : SearcherDialogEvent

    data object Dismiss : SearcherDialogEvent
}
