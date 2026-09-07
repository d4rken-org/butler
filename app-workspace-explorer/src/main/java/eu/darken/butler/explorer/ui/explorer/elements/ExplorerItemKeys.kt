package eu.darken.butler.explorer.ui.explorer.elements

import eu.darken.butler.explorer.core.engine.ExplorerItem

/**
 * List keys for [items], disambiguated only where they would collide.
 *
 * A file item's id is its path, and two documents in one directory can carry the same display name,
 * which makes those paths equal. Compose rejects duplicate keys outright, so the second occurrence
 * takes the whole listing down before it renders.
 *
 * Only repeats are suffixed, so an item whose id is unique keeps exactly the key it had, and with it
 * its scroll position and item animations.
 *
 *     ["a.txt", "dup.txt", "dup.txt"] -> ["a.txt", "dup.txt", "dup.txt#2"]
 */
internal fun List<ExplorerItem>.uniqueItemKeys(): List<String> {
    val seen = mutableMapOf<String, Int>()
    return map { item ->
        val occurrence = seen.merge(item.id, 1, Int::plus)!!
        if (occurrence == 1) item.id else "${item.id}#$occurrence"
    }
}
