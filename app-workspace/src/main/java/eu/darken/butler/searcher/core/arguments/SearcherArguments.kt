package eu.darken.butler.searcher.core.arguments

import eu.darken.butler.searcher.core.ContentQuery
import eu.darken.butler.searcher.core.FilenameQuery
import eu.darken.butler.searcher.core.SearchTarget
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
        val startSearch: Boolean = false,
    ) : SearcherArguments
}
