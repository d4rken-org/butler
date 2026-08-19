package eu.darken.butler.workspace.core.operations.history

import eu.darken.butler.workspace.core.operations.Operation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Domain projection of a single history row + its affected paths, surfaced from
 * [OperationHistoryRepo] to UI ViewModels. Decouples UI from Room types.
 */
data class HistoryEntry(
    val id: String,
    val kind: Operation.Metadata.Kind,
    val intent: Operation.Metadata.Intent?,
    val originType: OriginType,
    val originWorkspaceId: String,
    val title: String,
    val description: String,
    val summary: String?,
    val startedAt: Instant,
    val completedAt: Instant,
    val duration: Duration,
    val outcome: HistoryOutcome,
    val errorMessage: String?,
    val errorClass: String?,
    val affectedPathsCount: Int,
    val partialErrorCount: Int,
    val pathsTruncated: Boolean,
    val paths: List<PathChange>,
    /**
     * Representative path for entries without reported changes (failed/cancelled ops), so the list
     * can still show a filename and folder. Null when the operation named no paths at all.
     */
    val primaryPath: String? = null,
) {
    enum class OriginType { EXPLORER, SEARCHER, SAVER, DEVELOPER, VIEWER }

    data class PathChange(
        val path: String,
        val previousPath: String?,
        val change: Operation.Report.PathChange.Change,
    )

    companion object {
        fun durationOf(startedAt: Instant, completedAt: Instant): Duration =
            (completedAt - startedAt).coerceAtLeast(Duration.ZERO)

        fun durationOf(durationMs: Long): Duration = durationMs.milliseconds
    }
}
