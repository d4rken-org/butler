package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.searcher.core.SearchItem

sealed interface SearchListItem {
    data class Result(
        val searchItem: SearchItem,
    ) : SearchListItem

    data class Error(
        val throwable: Throwable,
    ) : SearchListItem
}