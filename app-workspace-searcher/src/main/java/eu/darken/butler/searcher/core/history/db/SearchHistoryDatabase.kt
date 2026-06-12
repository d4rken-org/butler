package eu.darken.butler.searcher.core.history.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import eu.darken.butler.common.room.InstantConverter

@Database(
    entities = [SearchHistoryEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    InstantConverter::class,
    SearchQueryConverter::class
)
abstract class SearchHistoryDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
