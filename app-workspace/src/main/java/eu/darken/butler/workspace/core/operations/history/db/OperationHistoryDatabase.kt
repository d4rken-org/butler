package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import eu.darken.butler.common.room.InstantConverter

@Database(
    entities = [
        OperationHistoryEntity::class,
        OperationHistoryPathEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(InstantConverter::class)
abstract class OperationHistoryDatabase : RoomDatabase() {
    abstract fun operationHistoryDao(): OperationHistoryDao
}
