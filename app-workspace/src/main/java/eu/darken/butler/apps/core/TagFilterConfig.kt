package eu.darken.butler.apps.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Tag-based filter configuration.
 *
 * @property includeTags Apps must have ALL of these tags (AND logic)
 * @property excludeTags Apps are excluded if they have ANY of these tags (OR logic)
 */
@Serializable
@Parcelize
data class TagFilterConfig(
    val includeTags: Set<AppTag> = emptySet(),
    val excludeTags: Set<AppTag> = emptySet(),
) : Parcelable {
    val isEmpty: Boolean
        get() = includeTags.isEmpty() && excludeTags.isEmpty()
}
