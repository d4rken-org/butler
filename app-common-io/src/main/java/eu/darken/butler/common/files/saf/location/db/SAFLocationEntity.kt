package eu.darken.butler.common.files.saf.location.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for SAF location user preferences.
 *
 * Stores user customizations like custom labels and hidden state
 * for each SAF location, keyed by location ID.
 */
@Entity(
    tableName = "saf_location_preferences",
    indices = [
        Index(value = ["locationId"], unique = true)
    ]
)
data class SAFLocationEntity(
    @PrimaryKey
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
