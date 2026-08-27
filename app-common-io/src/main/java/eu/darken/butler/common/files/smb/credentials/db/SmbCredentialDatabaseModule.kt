package eu.darken.butler.common.files.smb.credentials.db

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
object SmbCredentialDatabaseModule {

    private val TAG = logTag("SMB", "Credentials", "Database")

    @Provides
    @Singleton
    fun provideSmbCredentialDatabase(
        @ApplicationContext context: Context
    ): SmbCredentialDatabase = Room.databaseBuilder(
        context,
        SmbCredentialDatabase::class.java,
        "smb_credentials.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for SMB credential database" }
            fallbackToDestructiveMigration(true)
        }
        addMigrations(*SmbCredentialDatabase.MIGRATIONS)
    }.build()

    @Provides
    @Singleton
    fun provideSmbCredentialsDao(database: SmbCredentialDatabase): SmbCredentialsDao = database.smbCredentials()
}
