package eu.darken.butler.common.files.saf.location.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SAFLocationEntity::class],
    version = 1,
    exportSchema = true
)
abstract class SAFLocationDatabase : RoomDatabase() {
    abstract fun safLocations(): SAFLocationsDao
}
