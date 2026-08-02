package eu.darken.butler.explorer.core.sorting.rules.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * A sort rule the user saved for one folder.
 *
 * [followsDefault] is a column of its own rather than a null [mode]: a null mode would conflate the
 * intentional "use the default here" marker with a value written by a newer build or a corrupt row,
 * and such a row would then silently suppress valid ancestor rules.
 */
@Entity(tableName = "folder_sort_rules")
data class FolderSortRuleEntity(
    /** Encoded path key, see `APath.sortPathKey()`. */
    @PrimaryKey val pathKey: String,
    /** Serialized [eu.darken.butler.common.files.APath], for display only. */
    val path: String,
    val followsDefault: Boolean,
    /** Raw `SortSettings.Mode` name; an unknown value makes the row unusable rather than a marker. */
    val mode: String?,
    val reversed: Boolean,
    /** Whether the rule also covers everything below the folder. */
    val subtree: Boolean,
    val updatedAt: Instant,
)
