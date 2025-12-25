package eu.darken.butler.editor.core.engine

/**
 * Configuration options for text search operations.
 */
data class SearchOptions(
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWord: Boolean = false,
)
