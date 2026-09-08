package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation

/**
 * Attaches the sizes [sizes] knows for this listing's folders.
 *
 * Decorating here rather than in the page keeps one set of item instances for the listing, the
 * selection and the item info, which compare by data-class equality.
 */
internal fun ExplorerLocation.withDirectorySizes(sizes: DirectorySizeStore.Snapshot): ExplorerLocation {
    if (this !is ExplorerLocation.Directory) return this
    val items = items ?: return this
    val scan = sizes.scanFor(path) ?: return this
    return copy(
        items = items.map {
            if (it is ExplorerItem.RegularDirectory) it.copy(computedSize = scan.sizes[it.path.path]) else it
        },
    )
}
