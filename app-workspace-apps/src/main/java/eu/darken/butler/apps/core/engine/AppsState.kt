package eu.darken.butler.apps.core.engine

import kotlinx.serialization.Serializable

data class AppsState(
    val apps: List<AppItem> = emptyList(),
    val filteredApps: List<AppItem> = emptyList(),
    val filterConfig: FilterConfig = FilterConfig(),
    val sortMode: SortMode = SortMode.NAME_ASC,
    val searchQuery: String = "",
    val selectedAppIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
) {
    val isMultiSelectMode: Boolean
        get() = selectedAppIds.isNotEmpty()

    @Serializable
    data class FilterConfig(
        val showSystemApps: Boolean = false,
        val showDisabledApps: Boolean = false,
        val showUserApps: Boolean = true,
        val showEnabledApps: Boolean = true,
    ) {
        fun matches(item: AppItem): Boolean {
            if (item.isSystemApp && !showSystemApps) return false
            if (!item.isSystemApp && !showUserApps) return false
            if (!item.isEnabled && !showDisabledApps) return false
            if (item.isEnabled && !showEnabledApps) return false
            return true
        }
    }
}
