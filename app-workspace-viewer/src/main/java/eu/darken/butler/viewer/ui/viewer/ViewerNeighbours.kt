package eu.darken.butler.viewer.ui.viewer

import eu.darken.butler.common.files.APath

/**
 * Where one step lands from [current]. Both neighbours null means the file is alone in its listing.
 * [current] is what the snapshot was resolved for: the page state only accepts it while it matches
 * the file on display, so a step never acts on the previous file's neighbours.
 */
data class ViewerNeighbours(
    val current: APath<*>,
    val previous: APath<*>?,
    val next: APath<*>?,
)

/** Null when [current] is not in [files]: the listing moved on, so there is nothing to step through. */
internal fun resolveNeighbours(current: APath<*>, files: List<APath<*>>): ViewerNeighbours? {
    val index = files.indexOf(current)
    if (index < 0) return null
    return ViewerNeighbours(
        current = current,
        previous = files.getOrNull(index - 1),
        next = files.getOrNull(index + 1),
    )
}
