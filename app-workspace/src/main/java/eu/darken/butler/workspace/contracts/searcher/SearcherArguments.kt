package eu.darken.butler.workspace.contracts.searcher

import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating a Searcher workspace.
 * Sealed interface enables compile-time exhaustiveness checking.
 */
@Serializable
sealed interface SearcherArguments : Workspace.Arguments {
    override val type: Workspace.Type get() = Workspace.Type.SEARCHER

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        val startTargets: List<SearchTarget>? = null,
        val filenameQuery: FilenameQuery? = null,
        val contentQuery: ContentQuery? = null,
        val filter: SearchFilter? = null,
        val startSearch: Boolean = false,
    ) : SearcherArguments
}
