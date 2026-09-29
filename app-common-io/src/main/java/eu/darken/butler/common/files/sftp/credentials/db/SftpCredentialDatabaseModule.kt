package eu.darken.butler.common.files.sftp.credentials.db

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
object SftpCredentialDatabaseModule {

    private val TAG = logTag("SFTP", "Credentials", "Database")

    @Provides
    @Singleton
    fun provideSftpCredentialDatabase(
        @ApplicationContext context: Context
    ): SftpCredentialDatabase = Room.databaseBuilder(
        context,
        SftpCredentialDatabase::class.java,
        "sftp_credentials.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for SFTP credential database" }
            fallbackToDestructiveMigration(true)
        }
        addMigrations(*SftpCredentialDatabase.MIGRATIONS)
    }.build()

    @Provides
    @Singleton
    fun provideSftpCredentialsDao(database: SftpCredentialDatabase): SftpCredentialsDao = database.sftpCredentials()
}
