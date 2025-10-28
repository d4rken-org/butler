package eu.darken.butler.searcher.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchTarget

sealed interface SearcherCommand {
    data class Search(
        val query: String,
        val targets: List<SearchTarget>,
        val filter: SearchQuery.Filter = SearchQuery.Filter(),
        val options: SearchQuery.Options = SearchQuery.Options(),
        val saveToHistory: Boolean = false,
    ) : SearcherCommand

    data object Cancel : SearcherCommand

    data object Clear : SearcherCommand

    data class Delete(
        val targets: Set<APath<*>>,
    ) : SearcherCommand
}
