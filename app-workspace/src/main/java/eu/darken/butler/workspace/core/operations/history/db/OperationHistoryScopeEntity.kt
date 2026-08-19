package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Search index for the path-scope filter: every path an operation touched, intended to touch, or
 * whose folder it acted in. Never rendered as a change - [OperationHistoryPathEntity] is the audit
 * record of what actually happened.
 *
 * [sortIndex] preserves insertion order because Room's generated child queries carry no ORDER BY.
 */
@Entity(
    tableName = "operation_history_scope",
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
data class OperationHistoryScopeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationHistoryId: String,
    /** `path.userReadablePath.get(context)` snapshot */
    val path: String,
    val sortIndex: Int,
)
