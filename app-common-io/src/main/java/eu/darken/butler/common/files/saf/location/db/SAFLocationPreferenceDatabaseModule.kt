package eu.darken.butler.common.files.saf.location.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SAFLocationPreferenceDatabaseModule {

    private val TAG = logTag("SAF", "Location", "Preference", "Database")

    @Provides
    @Singleton
    fun provideSAFLocationPreferenceDatabase(
        @ApplicationContext context: Context
    ): SAFLocationPreferenceDatabase = Room.databaseBuilder(
        context,
        SAFLocationPreferenceDatabase::class.java,
        "saf_location_preferences.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for SAF location preferences database" }
            fallbackToDestructiveMigration(true)
        }
    }.build()

    @Provides
    @Singleton
    fun provideSAFLocationPreferenceDao(database: SAFLocationPreferenceDatabase): SAFLocationPreferenceDao {
        return database.safLocationPreferenceDao()
    }
}
