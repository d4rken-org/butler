package eu.darken.butler.explorer.core.sorting.rules.db

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
object FolderSortRuleDatabaseModule {

    private val TAG = logTag("Explorer", "Sorting", "Rules", "Database")

    @Provides
    @Singleton
    fun provideFolderSortRuleDatabase(
        @ApplicationContext context: Context
    ): FolderSortRuleDatabase = Room.databaseBuilder(
        context,
        FolderSortRuleDatabase::class.java,
        "folder_sort_rules.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for folder sort rule database" }
            fallbackToDestructiveMigration(true)
        }
        addMigrations(*FolderSortRuleDatabase.MIGRATIONS)
    }.build()

    @Provides
    @Singleton
    fun provideFolderSortRuleDao(database: FolderSortRuleDatabase): FolderSortRuleDao {
        return database.folderSortRuleDao()
    }
}
