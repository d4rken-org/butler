package eu.darken.butler.searcher.core.history.db

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
object SearchHistoryDatabaseModule {

    private val TAG = logTag("Searcher", "History", "Database")

    @Provides
    @Singleton
    fun provideSearchHistoryDatabase(
        @ApplicationContext context: Context
    ): SearchHistoryDatabase = Room.databaseBuilder(
        context,
        SearchHistoryDatabase::class.java,
        "search_history.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for search history database" }
            fallbackToDestructiveMigration(true)
        }
    }.build()

    @Provides
    @Singleton
    fun provideSearchHistoryDao(database: SearchHistoryDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }
}