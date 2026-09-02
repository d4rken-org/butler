package eu.darken.butler.common.trash.db

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
import eu.darken.butler.common.files.room.APathConverter
import eu.darken.butler.common.files.room.APathLookupConverter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TrashDatabaseModule {

    private val TAG = logTag("Trash", "Database")

    @Provides
    @Singleton
    fun provideTrashDatabase(
        @ApplicationContext context: Context,
        aPathConverter: APathConverter,
        aPathLookupConverter: APathLookupConverter,
    ): TrashDatabase = Room.databaseBuilder(
        context,
        TrashDatabase::class.java,
        "trash.db"
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for trash database" }
            fallbackToDestructiveMigration()
        }
        addMigrations(*TrashDatabase.MIGRATIONS)
        addTypeConverter(aPathConverter)
        addTypeConverter(aPathLookupConverter)
    }.build()
}
