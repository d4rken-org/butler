package eu.darken.butler.apps.core.engine

import androidx.annotation.StringRes
import eu.darken.butler.apps.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tags that describe app characteristics for display and filtering.
 * This is a pure data class - UI colors are provided via extension in AppTagColors.kt.
 */
@Serializable
sealed class AppTag(
    @StringRes open val labelRes: Int,
    open val priority: Int,
) {
    @Serializable
    @SerialName("disabled")
    data object Disabled : AppTag(
        labelRes = R.string.apps_tag_disabled_label,
        priority = 5,
    )

    @Serializable
    @SerialName("system")
    data object System : AppTag(
        labelRes = R.string.apps_tag_system_label,
        priority = 10,
    )

    @Serializable
    @SerialName("sideloaded")
    data object Sideloaded : AppTag(
        labelRes = R.string.apps_tag_sideloaded_label,
        priority = 15,
    )

    @Serializable
    @SerialName("updated_system")
    data object UpdatedSystem : AppTag(
        labelRes = R.string.apps_tag_updated_label,
        priority = 20,
    )

    @Serializable
    @SerialName("debug")
    data object Debug : AppTag(
        labelRes = R.string.apps_tag_debug_label,
        priority = 25,
    )

    @Serializable
    @SerialName("split_apk")
    data object SplitApk : AppTag(
        labelRes = R.string.apps_tag_split_label,
        priority = 30,
    )

    /**
     * Tag for apps installed in a specific user profile.
     *
     * Note: equals/hashCode only use handleId so label changes don't break filter matching.
     */
    @Serializable
    @SerialName("user")
    data class User(
        val handleId: Int,
        val label: String? = null,
    ) : AppTag(
        labelRes = R.string.apps_tag_user_label,
        priority = 1,
    ) {
        override fun equals(other: Any?) = other is User && handleId == other.handleId
        override fun hashCode() = handleId.hashCode()
    }

    // Virtual tags for filtering (match apps that are NOT system/disabled)

    @Serializable
    @SerialName("enabled")
    data object Enabled : AppTag(
        labelRes = R.string.apps_filter_tag_enabled_label,
        priority = 6,
    )

    @Serializable
    @SerialName("user_app")
    data object UserApp : AppTag(
        labelRes = R.string.apps_filter_tag_user_app_label,
        priority = 11,
    )

    companion object {
        /**
         * All standard tags for the filter dialog (excludes dynamic User profile tags).
         */
        val standardTags: List<AppTag> = listOf(
            System,
            UserApp,
            Enabled,
            Disabled,
            Sideloaded,
            UpdatedSystem,
            Debug,
            SplitApk,
        )
    }
}
