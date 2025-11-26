package eu.darken.butler.common.trash.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(
    tableName = "trash_items",
    indices = [
        Index(value = ["deletedAt"]),
    ]
)
data class TrashEntity(
    @PrimaryKey val id: Uuid = Uuid.random(),
    val originalPath: APath<*>,
    val originalLookup: APathLookup<*>,
    val trashPath: APath<*>,
    val deletedAt: Instant,
    val size: Long,
)