package eu.darken.butler.searcher.core

import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import kotlinx.serialization.Serializable

@Serializable
data class SearchDefaults(
    val filename: FilenameQuery = FilenameQuery(),
    val content: ContentQuery = ContentQuery(),
    val contentSearchEnabled: Boolean = false,
)
