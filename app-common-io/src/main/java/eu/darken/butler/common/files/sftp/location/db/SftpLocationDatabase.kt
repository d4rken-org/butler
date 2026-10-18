package eu.darken.butler.common.files.sftp.location.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.darken.butler.common.room.InstantConverter
import eu.darken.butler.common.room.UuidConverter

@Database(
    entities = [SftpLocationEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(
    UuidConverter::class,
    InstantConverter::class,
)
abstract class SftpLocationDatabase : RoomDatabase() {
    abstract fun sftpLocations(): SftpLocationsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sftp_locations ADD COLUMN rememberPassphrase INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
