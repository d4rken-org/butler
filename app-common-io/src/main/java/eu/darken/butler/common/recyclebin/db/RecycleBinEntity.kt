package eu.darken.butler.common.recyclebin.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(
    tableName = "recycle_bin_items",
    indices = [
        Index(value = ["deletedAt"]),
    ]
)
data class RecycleBinEntity(
    @PrimaryKey val id: Uuid = Uuid.random(),
    val originalPath: APath<*>,
    val originalLookup: APathLookup<*>,
    val recycleBinPath: APath<*>,
    val deletedAt: Instant,
    val size: Long,
)