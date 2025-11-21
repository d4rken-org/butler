package eu.darken.butler.common.recyclebin.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(
    tableName = "recycle_bin_items",
    indices = [
        Index(value = ["deletedAt"]),
    ]
)
data class RecycleBinEntity(
    @PrimaryKey val id: String = Uuid.random().toString(),
    val originalPath: String,
    val recycleBinPath: String,
    val deletedAt: Instant,
    val size: Long,
)