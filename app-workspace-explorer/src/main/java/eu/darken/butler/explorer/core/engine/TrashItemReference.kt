package eu.darken.butler.explorer.core.engine

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Lightweight, parcelable reference to a root-level trash item.
 * Used for navigation state persistence without carrying full lookup data.
 */
@Parcelize
data class TrashItemReference(
    val itemId: Uuid,
    val displayName: @RawValue CaString,
    val originalPath: @RawValue APath<*>,
    val trashPath: @RawValue APath<*>,
    val deletedAt: Instant,
) : Parcelable {
    companion object {
        fun from(item: ExplorerItem.Trash.Root): TrashItemReference {
            val trashLookup = item.trashLookup
                ?: error("Cannot reference unavailable trash item")
            return TrashItemReference(
                itemId = item.itemId,
                displayName = item.displayName,
                originalPath = item.originalLookup.lookedUp,
                trashPath = trashLookup.lookedUp,
                deletedAt = item.deletedAt,
            )
        }
    }
}
