package eu.darken.butler.common.recyclebin.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import eu.darken.butler.common.room.InstantConverter

@Database(
    entities = [RecycleBinEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    InstantConverter::class,
)
abstract class RecycleBinDatabase : RoomDatabase() {
    abstract fun recycleBinDao(): RecycleBinDao
}