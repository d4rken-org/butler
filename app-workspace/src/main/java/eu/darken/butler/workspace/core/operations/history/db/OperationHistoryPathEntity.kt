package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "operation_history_paths",
    foreignKeys = [
        ForeignKey(
            entity = OperationHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["operationHistoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationHistoryId"]),
        Index(value = ["path"]),
    ],
)
data class OperationHistoryPathEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationHistoryId: String,
    /** `path.userReadablePath.get(context)` snapshot */
    val path: String,
    /** For MOVED entries (rename source). Null for non-move changes or untracked. */
    val previousPath: String?,
    /** [eu.darken.butler.workspace.core.operations.Operation.Report.PathChange.Change] name. */
    val change: String,
    val sortIndex: Int,
)
