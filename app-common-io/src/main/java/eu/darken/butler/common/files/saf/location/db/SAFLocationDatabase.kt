package eu.darken.butler.common.files.saf.location.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [SAFLocationEntity::class],
    version = 1,
    exportSchema = true
)
abstract class SAFLocationDatabase : RoomDatabase() {
    abstract fun safLocations(): SAFLocationsDao

    companion object {
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
