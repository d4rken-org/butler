package eu.darken.butler.workspace.core.operations.history.db

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
object OperationHistoryDatabaseModule {

    private val TAG = logTag("Workspace", "Operations", "History", "Database")

    @Provides
    @Singleton
    fun provideOperationHistoryDatabase(
        @ApplicationContext context: Context,
    ): OperationHistoryDatabase = Room.databaseBuilder(
        context,
        OperationHistoryDatabase::class.java,
        "operation_history.db",
    ).apply {
        if (BuildConfigWrap.DEBUG) {
            log(TAG) { "Debug mode: Enabling destructive migration for operation history database" }
            fallbackToDestructiveMigration(true)
        }
    }.build()

    @Provides
    @Singleton
    fun provideOperationHistoryDao(database: OperationHistoryDatabase): OperationHistoryDao =
        database.operationHistoryDao()
}
