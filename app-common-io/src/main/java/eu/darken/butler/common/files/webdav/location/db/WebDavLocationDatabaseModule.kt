package eu.darken.butler.common.files.webdav.location.db

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
object WebDavLocationDatabaseModule {

    private val TAG = logTag("WebDAV", "Location", "Database")

    @Provides
    @Singleton
    fun provideWebDavLocationDatabase(
        @ApplicationContext context: Context
    ): WebDavLocationDatabase = Room.databaseBuilder(
        context,
        WebDavLocationDatabase::class.java,
        "webdav_locations.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for WebDAV location database" }
            fallbackToDestructiveMigration(true)
        }
        addMigrations(*WebDavLocationDatabase.MIGRATIONS)
    }.build()

    @Provides
    @Singleton
    fun provideWebDavLocationsDao(database: WebDavLocationDatabase): WebDavLocationsDao = database.webDavLocations()
}
