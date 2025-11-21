package eu.darken.butler.workspace.core.session.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import eu.darken.butler.common.room.InstantConverter
import eu.darken.butler.workspace.core.serialization.WorkspaceIdConverter
import eu.darken.butler.workspace.core.serialization.WorkspaceTypeConverter

@Database(
    entities = [
        WorkspaceSessionEntity::class,
        WorkspaceInstanceEntity::class,
    ],
    version = 1,
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
}
