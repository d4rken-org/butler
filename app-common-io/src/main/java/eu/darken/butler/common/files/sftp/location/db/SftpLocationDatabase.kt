package eu.darken.butler.common.files.sftp.location.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import eu.darken.butler.common.room.InstantConverter
import eu.darken.butler.common.room.UuidConverter

@Database(
    entities = [SftpLocationEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    UuidConverter::class,
    InstantConverter::class,
)
abstract class SftpLocationDatabase : RoomDatabase() {
    abstract fun sftpLocations(): SftpLocationsDao

    companion object {
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
