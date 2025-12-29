package eu.darken.butler.apps.core.engine

import eu.darken.butler.apps.core.TagFilterConfig

/**
 * Extension to check if an [AppItem] matches this [TagFilterConfig].
 */
fun TagFilterConfig.matches(item: AppItem): Boolean {
    val itemTags = item.toTagSet()

    // Exclude: reject if item has ANY excluded tag
    if (excludeTags.any { it in itemTags }) return false

    // Include: must have ALL selected tags (AND)
    if (includeTags.isEmpty()) return true
    return includeTags.all { it in itemTags }
}
