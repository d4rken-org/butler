package eu.darken.butler.common.files.saf.location.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SAFLocationPreferenceEntity::class],
    version = 1,
    exportSchema = true
)
abstract class SAFLocationPreferenceDatabase : RoomDatabase() {
    abstract fun safLocationPreferenceDao(): SAFLocationPreferenceDao
}
