package eu.darken.butler.apps.core.engine

import eu.darken.butler.apps.core.AppTag
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

/**
 * Tri-state filter state for a tag in the filter UI.
 */
enum class FilterState {
    /** Tag is not used in filtering */
    NEUTRAL,
    /** Apps must have this tag (include filter) */
    INCLUDE,
    /** Apps with this tag are excluded */
    EXCLUDE,
}

/**
 * Gets the current filter state for a specific tag.
 */
fun TagFilterConfig.getTagState(tag: AppTag): FilterState = when {
    tag in includeTags -> FilterState.INCLUDE
    tag in excludeTags -> FilterState.EXCLUDE
    else -> FilterState.NEUTRAL
}

/**
 * Returns a new config with the tag set to the specified state,
 * automatically removing any conflicting tags.
 */
fun TagFilterConfig.withTagState(tag: AppTag, state: FilterState): TagFilterConfig {
    val conflicting = tag.conflictingTag
    return when (state) {
        FilterState.NEUTRAL -> copy(
            includeTags = includeTags - tag,
            excludeTags = excludeTags - tag,
        )
        FilterState.INCLUDE -> copy(
            includeTags = (includeTags + tag).minusIfNotNull(conflicting),
            excludeTags = (excludeTags - tag).minusIfNotNull(conflicting),
        )
        FilterState.EXCLUDE -> copy(
            includeTags = (includeTags - tag).minusIfNotNull(conflicting),
            excludeTags = (excludeTags + tag).minusIfNotNull(conflicting),
        )
    }
}

/**
 * Cycles to the next filter state: NEUTRAL → INCLUDE → EXCLUDE → NEUTRAL
 */
fun FilterState.next(): FilterState = when (this) {
    FilterState.NEUTRAL -> FilterState.INCLUDE
    FilterState.INCLUDE -> FilterState.EXCLUDE
    FilterState.EXCLUDE -> FilterState.NEUTRAL
}

private fun Set<AppTag>.minusIfNotNull(tag: AppTag?): Set<AppTag> =
    if (tag != null) this - tag else this
