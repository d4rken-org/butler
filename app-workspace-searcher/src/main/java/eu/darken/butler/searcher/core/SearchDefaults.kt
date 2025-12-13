package eu.darken.butler.searcher.core

import kotlinx.serialization.Serializable

@Serializable
data class SearchDefaults(
    val filename: FilenameQuery = FilenameQuery(),
    val content: ContentQuery = ContentQuery(),
    val contentSearchEnabled: Boolean = false,
)
