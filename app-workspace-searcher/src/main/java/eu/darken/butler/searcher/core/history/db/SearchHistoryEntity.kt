package eu.darken.butler.searcher.core.history.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid
import kotlin.time.Instant

@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["searchedAt"]),
        Index(value = ["baseQuery"])
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey val id: String = Uuid.random().toString(),
    val baseQuery: String,
    val rawQuery: String,
    val searchedAt: Instant,
    val resultCount: Int? = null
)