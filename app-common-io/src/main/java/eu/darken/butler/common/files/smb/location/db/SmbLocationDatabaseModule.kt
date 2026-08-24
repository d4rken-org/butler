package eu.darken.butler.common.files.smb.location.db

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
object SmbLocationDatabaseModule {

    private val TAG = logTag("SMB", "Location", "Database")

    @Provides
    @Singleton
    fun provideSmbLocationDatabase(
        @ApplicationContext context: Context
    ): SmbLocationDatabase = Room.databaseBuilder(
        context,
        SmbLocationDatabase::class.java,
        "smb_locations.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for SMB location database" }
            fallbackToDestructiveMigration(true)
        }
        addMigrations(*SmbLocationDatabase.MIGRATIONS)
    }.build()

    @Provides
    @Singleton
    fun provideSmbLocationsDao(database: SmbLocationDatabase): SmbLocationsDao = database.smbLocations()
}
