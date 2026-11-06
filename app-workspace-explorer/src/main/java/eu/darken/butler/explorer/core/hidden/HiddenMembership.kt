package eu.darken.butler.explorer.core.hidden

import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.sorting.rules.sortAncestorKeys
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey

/**
 * Which listing entries the hide list covers. Only path entries can be hidden this way, so trashed
 * entries never are.
 *
 * With [includeAncestors], an entry also counts when any folder above it is on the list: Recent shows
 * files from all over storage, and a hidden folder would otherwise leak its contents there.
 */
data class HiddenMembership(
    val keys: Set<String>,
    val includeAncestors: Boolean,
) {
    fun isUserHidden(item: ExplorerItem): Boolean {
        if (keys.isEmpty() || item !is ExplorerItem.Path) return false
        return if (includeAncestors) {
            item.path.sortAncestorKeys().any { it in keys }
        } else {
            item.path.sortPathKey() in keys
        }
    }

    companion object {
        val NONE = HiddenMembership(keys = emptySet(), includeAncestors = false)
    }
}
