package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Room projection joining one [OperationHistoryEntity] with its [OperationHistoryPathEntity] children.
 * Used by DAO queries that need both the operation row and its affected paths.
 */
data class OperationHistoryWithPaths(
    @Embedded val entry: OperationHistoryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "operationHistoryId",
    )
    val paths: List<OperationHistoryPathEntity>,
)
