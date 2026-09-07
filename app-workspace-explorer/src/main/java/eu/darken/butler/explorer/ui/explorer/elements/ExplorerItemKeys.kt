package eu.darken.butler.explorer.ui.explorer.elements

import eu.darken.butler.explorer.core.engine.ExplorerItem

/**
 * List keys for [this], disambiguated only where they would collide.
 *
 * A file item's id is its path, and two documents in one directory can carry the same display name,
 * which makes those paths equal. Compose rejects duplicate keys outright, so the second occurrence
 * takes the whole listing down before it renders.
 *
 * Only repeats are suffixed, so an item whose id is unique keeps exactly the key it had, and with it
 * its scroll position and item animations.
 *
 *     ["a.txt", "dup.txt", "dup.txt"] -> ["a.txt", "dup.txt", "dup.txt#2"]
 *
 * A suffix may not land on a name some other row already owns: a directory holding `dup.txt` twice
 * *and* a literal `dup.txt#2` would otherwise collide all over again, which is the crash this exists
 * to prevent. Every id in the list is reserved up front and suffixes skip anything taken.
 */
internal fun List<ExplorerItem>.uniqueItemKeys(): List<String> {
    val taken = mapTo(mutableSetOf()) { it.id }
    val seen = mutableSetOf<String>()
    return map { item ->
        if (seen.add(item.id)) return@map item.id

        var occurrence = 2
        var candidate = "${item.id}#$occurrence"
        while (!taken.add(candidate)) {
            occurrence++
            candidate = "${item.id}#$occurrence"
        }
        candidate
    }
}

/**
 * The ids [this] carries exactly once.
 *
 * Anything repeated is genuinely ambiguous: the whole Explorer keys selection, drag payloads and
 * highlighting on the item id, so a repeated id addresses no single item. Callers that need to map a
 * key back to one item must leave those rows out rather than pick arbitrarily.
 */
internal fun List<ExplorerItem>.unambiguousItemIds(): Set<String> =
    groupingBy { it.id }.eachCount().filterValues { it == 1 }.keys
