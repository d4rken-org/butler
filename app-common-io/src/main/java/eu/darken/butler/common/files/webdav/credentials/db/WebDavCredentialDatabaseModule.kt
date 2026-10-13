package eu.darken.butler.common.files.webdav.credentials.db

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
object WebDavCredentialDatabaseModule {

    private val TAG = logTag("WebDAV", "Credentials", "Database")

    @Provides
    @Singleton
    fun provideWebDavCredentialDatabase(
        @ApplicationContext context: Context
    ): WebDavCredentialDatabase = Room.databaseBuilder(
        context,
        WebDavCredentialDatabase::class.java,
        "webdav_credentials.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for WebDAV credential database" }
            fallbackToDestructiveMigration(true)
        }
        addMigrations(*WebDavCredentialDatabase.MIGRATIONS)
    }.build()

    @Provides
    @Singleton
    fun provideWebDavCredentialsDao(database: WebDavCredentialDatabase): WebDavCredentialsDao = database.webDavCredentials()
}
