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

/**
 * Removes duplicate results that share the same absolute path.
 *
 * Overlapping search roots (e.g. `/storage/emulated/0` and
 * `/storage/emulated/0/Download`) can surface the same file twice, which crashes the
 * results [androidx.compose.foundation.lazy.LazyColumn] because items are keyed by path.
 * Files with the same name in different directories have distinct full paths and are kept.
 */
fun List<SearchItem>.distinctByPath(): List<SearchItem> = distinctBy { it.path.path }
