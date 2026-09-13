package eu.darken.butler.explorer.core.favorites

import eu.darken.butler.common.files.APath
import kotlinx.serialization.Serializable

/**
 * A favorited path together with the name the user gave it.
 *
 * [label] is a display name for the favorite entry only — the folder on disk keeps its own name.
 */
@Serializable
data class FavoriteEntry(
    val path: APath<*>,
    val label: String? = null,
)
