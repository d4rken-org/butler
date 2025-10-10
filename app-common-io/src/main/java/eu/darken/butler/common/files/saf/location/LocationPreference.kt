package eu.darken.butler.common.files.saf.location

import kotlinx.serialization.Serializable

/**
 * User preferences for a single SAF location
 */
@Serializable
data class LocationPreference(
    /**
     * Location ID (matches SAFLocation.id)
     */
    val locationId: String,

    /**
     * User-provided custom label (null = use default name)
     */
    val userLabel: String? = null,

    /**
     * Whether this location is hidden from the UI
     */
    val isHidden: Boolean = false,
)