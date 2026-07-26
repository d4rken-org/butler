package eu.darken.butler.workspace.core.session.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.darken.butler.common.room.InstantConverter
import eu.darken.butler.workspace.core.serialization.WorkspaceIdConverter
import eu.darken.butler.workspace.core.serialization.WorkspaceTypeConverter

@Database(
    entities = [
        WorkspaceSessionEntity::class,
        WorkspaceInstanceEntity::class,
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(
    InstantConverter::class,
    WorkspaceUIStateConverter::class,
    WorkspaceTypeConverter::class,
    WorkspaceIdConverter::class,
)
abstract class WorkspaceSessionDatabase : RoomDatabase() {
    abstract fun sessionDao(): WorkspaceSessionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workspace_instances ADD COLUMN customTitle TEXT")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
