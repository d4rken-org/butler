package eu.darken.butler.common.recyclebin.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import eu.darken.butler.common.files.room.APathConverter
import eu.darken.butler.common.files.room.APathLookupConverter
import eu.darken.butler.common.room.InstantConverter
import eu.darken.butler.common.room.UuidConverter

@Database(
    entities = [RecycleBinEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    UuidConverter::class,
    InstantConverter::class,
    APathConverter::class,
    APathLookupConverter::class,
)
abstract class RecycleBinDatabase : RoomDatabase() {
    abstract fun recycleBinDao(): RecycleBinDao
}
