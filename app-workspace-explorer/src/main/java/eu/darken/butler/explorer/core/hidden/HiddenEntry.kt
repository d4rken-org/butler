package eu.darken.butler.explorer.core.hidden

import eu.darken.butler.common.files.APath
import kotlinx.serialization.Serializable

/**
 * A path the user hid from Explorer listings. Nothing on disk changes.
 *
 * [key] is the path's `sortPathKey()` at the time it was hidden; membership is decided by it alone.
 */
@Serializable
data class HiddenEntry(
    val key: String,
    val path: APath<*>,
)
