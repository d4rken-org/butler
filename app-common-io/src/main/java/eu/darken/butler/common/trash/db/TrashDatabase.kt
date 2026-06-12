package eu.darken.butler.common.trash.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import eu.darken.butler.common.files.room.APathConverter
import eu.darken.butler.common.files.room.APathLookupConverter
import eu.darken.butler.common.room.InstantConverter
import eu.darken.butler.common.room.UuidConverter

@Database(
    entities = [TrashEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    UuidConverter::class,
    InstantConverter::class,
    APathConverter::class,
    APathLookupConverter::class,
)
abstract class TrashDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao

    companion object {
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
