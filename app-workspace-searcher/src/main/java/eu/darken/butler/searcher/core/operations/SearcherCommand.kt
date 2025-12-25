package eu.darken.butler.searcher.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.ContentQuery
import eu.darken.butler.searcher.core.FilenameQuery
import eu.darken.butler.searcher.core.SearchFilter
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchTarget

sealed interface SearcherCommand {
    data class Search(
        val filenameQuery: FilenameQuery = FilenameQuery(),
        val contentQuery: ContentQuery = ContentQuery(),
        val targets: List<SearchTarget>,
        val filter: SearchFilter = SearchFilter(),
        val options: SearchQuery.Options = SearchQuery.Options(),
        val saveToHistory: Boolean = false,
    ) : SearcherCommand

    data object Cancel : SearcherCommand

    data object Clear : SearcherCommand

    data class Delete(
        val targets: Set<APath<*>>,
        val options: Options = Options(),
    ) : SearcherCommand {
        data class Options(
            val forcePermDelete: Boolean = false,
        )
    }

    /**
     * Target management command
     */
    data object AddDefaultPaths : SearcherCommand
}
