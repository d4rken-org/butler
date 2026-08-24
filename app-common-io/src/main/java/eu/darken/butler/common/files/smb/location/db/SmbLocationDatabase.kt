package eu.darken.butler.common.files.smb.location.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import eu.darken.butler.common.room.InstantConverter
import eu.darken.butler.common.room.UuidConverter

@Database(
    entities = [SmbLocationEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    UuidConverter::class,
    InstantConverter::class,
)
abstract class SmbLocationDatabase : RoomDatabase() {
    abstract fun smbLocations(): SmbLocationsDao

    companion object {
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
