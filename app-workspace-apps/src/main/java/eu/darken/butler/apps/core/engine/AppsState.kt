package eu.darken.butler.apps.core.engine

import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig

data class AppsState(
    val apps: List<AppItem> = emptyList(),
    val filteredApps: List<AppItem> = emptyList(),
    val filterConfig: TagFilterConfig = TagFilterConfig(),
    val sortSettings: SortSettings = SortSettings(),
    val searchQuery: String = "",
    val selectedAppIds: Set<InstallId> = emptySet(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isResolvingSizes: Boolean = false,
    val error: Throwable? = null,
) {
    val isMultiSelectMode: Boolean
        get() = selectedAppIds.isNotEmpty()
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
