package eu.darken.butler.apps.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tags that describe app characteristics for filtering.
 * This is a pure data class for serialization - UI properties (labelRes, priority, colors)
 * are provided via extensions in app-workspace-apps.
 */
@Serializable
@Parcelize
sealed class AppTag : Parcelable {
    @Serializable
    @SerialName("disabled")
    data object Disabled : AppTag()

    @Serializable
    @SerialName("system")
    data object System : AppTag()

    @Serializable
    @SerialName("sideloaded")
    data object Sideloaded : AppTag()

    @Serializable
    @SerialName("updated_system")
    data object UpdatedSystem : AppTag()

    @Serializable
    @SerialName("debug")
    data object Debug : AppTag()

    @Serializable
    @SerialName("split_apk")
    data object SplitApk : AppTag()

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
    ) : AppTag() {
        override fun equals(other: Any?) = other is User && handleId == other.handleId
        override fun hashCode() = handleId.hashCode()
    }

    // Virtual tags for filtering (match apps that are NOT system/disabled)

    @Serializable
    @SerialName("enabled")
    data object Enabled : AppTag()

    @Serializable
    @SerialName("user_app")
    data object UserApp : AppTag()

    companion object
}
