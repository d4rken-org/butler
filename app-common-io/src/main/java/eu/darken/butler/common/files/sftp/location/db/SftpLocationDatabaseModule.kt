package eu.darken.butler.common.files.sftp.location.db

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
object SftpLocationDatabaseModule {

    private val TAG = logTag("SFTP", "Location", "Database")

    @Provides
    @Singleton
    fun provideSftpLocationDatabase(
        @ApplicationContext context: Context
    ): SftpLocationDatabase = Room.databaseBuilder(
        context,
        SftpLocationDatabase::class.java,
        "sftp_locations.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for SFTP location database" }
            fallbackToDestructiveMigration(true)
        }
        addMigrations(*SftpLocationDatabase.MIGRATIONS)
    }.build()

    @Provides
    @Singleton
    fun provideSftpLocationsDao(database: SftpLocationDatabase): SftpLocationsDao = database.sftpLocations()
}
