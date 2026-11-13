package eu.darken.butler.common.files.smb.location.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.darken.butler.common.room.InstantConverter
import eu.darken.butler.common.room.UuidConverter

@Database(
    entities = [SmbLocationEntity::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(
    UuidConverter::class,
    InstantConverter::class,
)
abstract class SmbLocationDatabase : RoomDatabase() {
    abstract fun smbLocations(): SmbLocationsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE smb_locations ADD COLUMN lastSeenAt INTEGER")
            }
        }

        /** SQLite cannot drop NOT NULL in place, so the table is rebuilt with a nullable `share`. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smb_locations_new` (`locationId` TEXT NOT NULL, `label` TEXT, " +
                        "`host` TEXT NOT NULL, `port` INTEGER NOT NULL, `share` TEXT, `basePath` TEXT NOT NULL, " +
                        "`domain` TEXT, `username` TEXT, `authType` TEXT NOT NULL, " +
                        "`rememberCredential` INTEGER NOT NULL, `credentialVersion` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastSeenAt` INTEGER, " +
                        "PRIMARY KEY(`locationId`))"
                )
                db.execSQL(
                    "INSERT INTO `smb_locations_new` (locationId, label, host, port, share, basePath, domain, " +
                        "username, authType, rememberCredential, credentialVersion, createdAt, updatedAt, " +
                        "lastSeenAt) " +
                        "SELECT locationId, label, host, port, share, basePath, domain, username, authType, " +
                        "rememberCredential, credentialVersion, createdAt, updatedAt, lastSeenAt FROM smb_locations"
                )
                db.execSQL("DROP TABLE smb_locations")
                db.execSQL("ALTER TABLE smb_locations_new RENAME TO smb_locations")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
