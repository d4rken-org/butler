package eu.darken.butler.searcher.core.arguments

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
    ) : SearcherArguments
}
