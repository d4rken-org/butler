package eu.darken.butler.explorer.core.hidden

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey

/** One snapshot of the hide list; [keys] is derived from [entries], never cached on its own. */
data class HiddenPaths(
    val entries: List<HiddenEntry>,
) {
    val keys: Set<String> = entries.mapTo(mutableSetOf()) { it.key }

    /** Exact entry match; a path under a hidden folder is not itself on the list. */
    fun isHidden(path: APath<*>): Boolean = path.sortPathKey() in keys

    companion object {
        val EMPTY = HiddenPaths(emptyList())
    }
}
