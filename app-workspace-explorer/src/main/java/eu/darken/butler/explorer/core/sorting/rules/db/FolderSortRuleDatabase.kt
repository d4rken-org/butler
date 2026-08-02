package eu.darken.butler.explorer.core.sorting.rules.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import eu.darken.butler.common.room.InstantConverter

@Database(
    entities = [FolderSortRuleEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    InstantConverter::class
)
abstract class FolderSortRuleDatabase : RoomDatabase() {
    abstract fun folderSortRuleDao(): FolderSortRuleDao

    companion object {
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
