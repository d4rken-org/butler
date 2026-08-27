package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(
    tableName = "operation_history",
    indices = [
        Index(value = ["completedAt"]),
        Index(value = ["outcome"]),
        Index(value = ["kind"]),
    ],
)
data class OperationHistoryEntity(
    @PrimaryKey val id: String = Uuid.random().toString(),
    /** Operation.Metadata.Kind name */
    val kind: String,
    /** Operation.Metadata.Intent name, null when no intent override */
    val intent: String?,
    /** Origin discriminator: EXPLORER | SEARCHER | SAVER | DEVELOPER */
    val originType: String,
    /** Workspace ID at submit time. Forensic — workspace may no longer exist. */
    val originWorkspaceId: String,
    /** Resolved CaString snapshot at insert time. Locale frozen at write. */
    val title: String,
    /** Resolved CaString snapshot at insert time. */
    val description: String,
    /** Resolved [Operation.State.Completed.summary]. Null if not provided. */
    val summary: String?,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMs: Long,
    /** [eu.darken.butler.workspace.core.operations.history.HistoryOutcome] name */
    val outcome: String,
    val errorMessage: String?,
    val errorClass: String?,
    /** Total count of paths the op affected, even when [pathsTruncated]. */
    val affectedPathsCount: Int,
    /** For [outcome] = PARTIAL: number of sub-items that failed. 0 otherwise. */
    val partialErrorCount: Int = 0,
    /** True when [operation_history_paths] only stores a capped subset (default cap: 200). */
    val pathsTruncated: Boolean = false,
    /**
     * The path the operation was about: the report's subject, else the path plan's representative
     * path. Lets the list name a file and folder without loading child rows, and stays correct for
     * operations whose reported changes are an audit trail rather than a subject (an extraction's
     * entries, a recursive delete's descendants).
     */
    val primaryPath: String? = null,
)
