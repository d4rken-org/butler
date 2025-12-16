package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.searcher.core.SearchItem
import kotlin.time.Instant

sealed interface SearchListItem {
    data class Result(
        val searchItem: SearchItem,
    ) : SearchListItem

    data class Error(
        val throwable: Throwable,
        val timestamp: Instant,
    ) : SearchListItem
}