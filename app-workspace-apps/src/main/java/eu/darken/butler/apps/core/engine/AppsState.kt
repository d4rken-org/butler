package eu.darken.butler.apps.core.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class AppsState(
    val apps: List<AppItem> = emptyList(),
    val filteredApps: List<AppItem> = emptyList(),
    val filterConfig: FilterConfig = FilterConfig(),
    val sortSettings: SortSettings = SortSettings(),
    val searchQuery: String = "",
    val selectedAppIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
) {
    val isMultiSelectMode: Boolean
        get() = selectedAppIds.isNotEmpty()

    @Serializable
    data class FilterConfig(
        val appType: AppType = AppType.ALL,
        val status: Status = Status.ALL,
    ) {
        @Serializable
        enum class AppType {
            @SerialName("ALL") ALL,
            @SerialName("USER") USER,
            @SerialName("SYSTEM") SYSTEM,
        }

        @Serializable
        enum class Status {
            @SerialName("ALL") ALL,
            @SerialName("ENABLED") ENABLED,
            @SerialName("DISABLED") DISABLED,
        }

        fun matches(item: AppItem): Boolean {
            // Check app type
            when (appType) {
                AppType.USER -> if (item.isSystemApp) return false
                AppType.SYSTEM -> if (!item.isSystemApp) return false
                AppType.ALL -> {} // No filter
            }

            // Check status
            when (status) {
                Status.ENABLED -> if (!item.isEnabled) return false
                Status.DISABLED -> if (item.isEnabled) return false
                Status.ALL -> {} // No filter
            }

            return true
        }
    }
}
