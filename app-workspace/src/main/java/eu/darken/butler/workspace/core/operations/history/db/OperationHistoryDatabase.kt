package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.darken.butler.common.room.InstantConverter

@Database(
    entities = [
        OperationHistoryEntity::class,
        OperationHistoryPathEntity::class,
        OperationHistoryScopeEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(InstantConverter::class)
abstract class OperationHistoryDatabase : RoomDatabase() {
    abstract fun operationHistoryDao(): OperationHistoryDao

    companion object {
        /**
         * Adds the path-scope search index and the representative path column.
         *
         * Existing rows are dropped: they conflate reported changes with merely intended paths under
         * synthetic change labels and cannot be reclassified after the fact. History is disposable,
         * so wiping is honest where a guess would not be. The child tables are cleared explicitly
         * because foreign key enforcement is off while migrations run, so the cascade wouldn't fire.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `operation_history_scope` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`operationHistoryId` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, " +
                        "`sortIndex` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`operationHistoryId`) REFERENCES `operation_history`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_operation_history_scope_operationHistoryId` " +
                        "ON `operation_history_scope` (`operationHistoryId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_operation_history_scope_path` " +
                        "ON `operation_history_scope` (`path`)"
                )
                db.execSQL("ALTER TABLE operation_history ADD COLUMN primaryPath TEXT")
                db.execSQL("DELETE FROM operation_history_paths")
                db.execSQL("DELETE FROM operation_history")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
