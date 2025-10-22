package eu.darken.butler.searcher.ui.search

import eu.darken.butler.searcher.ui.search.rows.FileRowData
import kotlin.time.Instant

sealed interface SearchListItem {
    data class Result(
        val fileRowData: FileRowData,
    ) : SearchListItem

    data class Error(
        val throwable: Throwable,
        val timestamp: Instant,
    ) : SearchListItem
}
