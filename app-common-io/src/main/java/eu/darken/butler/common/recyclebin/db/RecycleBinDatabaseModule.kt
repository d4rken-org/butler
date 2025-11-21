package eu.darken.butler.common.recyclebin.db

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
object RecycleBinDatabaseModule {

    private val tag = logTag("RecycleBin", "Database")

    @Provides
    @Singleton
    fun provideRecycleBinDatabase(
        @ApplicationContext context: Context
    ): RecycleBinDatabase = Room.databaseBuilder(
        context,
        RecycleBinDatabase::class.java,
        "recycle_bin.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(tag) { "Debug mode: Enabling destructive migration for recycle bin database" }
            fallbackToDestructiveMigration()
        }
    }.build()

    @Provides
    @Singleton
    fun provideRecycleBinDao(database: RecycleBinDatabase): RecycleBinDao {
        return database.recycleBinDao()
    }
}