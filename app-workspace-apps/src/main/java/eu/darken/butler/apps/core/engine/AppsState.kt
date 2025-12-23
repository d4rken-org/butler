package eu.darken.butler.apps.core.engine

import kotlinx.serialization.Serializable

data class AppsState(
    val apps: List<AppItem> = emptyList(),
    val filteredApps: List<AppItem> = emptyList(),
    val filterConfig: TagFilterConfig = TagFilterConfig(),
    val sortSettings: SortSettings = SortSettings(),
    val searchQuery: String = "",
    val selectedAppIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
) {
    val isMultiSelectMode: Boolean
        get() = selectedAppIds.isNotEmpty()
}

/**
 * Tag-based filter configuration.
 *
 * @property includeTags Apps must have ALL of these tags (AND logic)
 * @property excludeTags Apps are excluded if they have ANY of these tags (OR logic)
 */
@Serializable
data class TagFilterConfig(
    val includeTags: Set<AppTag> = emptySet(),
    val excludeTags: Set<AppTag> = emptySet(),
) {
    fun matches(item: AppItem): Boolean {
        val itemTags = item.toTagSet()

        // Exclude: reject if item has ANY excluded tag
        if (excludeTags.any { it in itemTags }) return false

        // Include: must have ALL selected tags (AND)
        if (includeTags.isEmpty()) return true
        return includeTags.all { it in itemTags }
    }

    val isEmpty: Boolean
        get() = includeTags.isEmpty() && excludeTags.isEmpty()
}

/**
 * Converts an [AppItem] to a set of [AppTag]s for filter matching.
 * Includes both direct tags and virtual tags (Enabled, UserApp).
 */
fun AppItem.toTagSet(): Set<AppTag> = buildSet {
    // Direct tags from the item
    addAll(tags)

    // Virtual tags (inverse properties)
    if (isEnabled) add(AppTag.Enabled)
    if (!isSystemApp) add(AppTag.UserApp)
}
