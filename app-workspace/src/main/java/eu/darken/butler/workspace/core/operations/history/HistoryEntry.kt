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
    /** Per-app outcomes of a package operation, or the app an install installed. Empty otherwise. */
    val packages: List<PackageOutcome> = emptyList(),
    /**
     * The path the operation was about, used as the row label. Not necessarily [paths]`[0]`: an
     * extraction names its archive, a recursive delete names the folder that was selected. Null
     * when neither the report nor the path plan named one.
     */
    val primaryPath: String? = null,
) {
    enum class OriginType { EXPLORER, SEARCHER, SAVER, DEVELOPER, VIEWER, APPS }

    data class PathChange(
        val path: String,
        val previousPath: String?,
        val change: Operation.Report.Paths.PathChange.Change,
    )

    data class PackageOutcome(
        val label: String,
        val packageName: String,
        val status: Operation.Report.Packages.Outcome.Status,
        val errorMessage: String?,
    )

    companion object {
        fun durationOf(startedAt: Instant, completedAt: Instant): Duration =
            (completedAt - startedAt).coerceAtLeast(Duration.ZERO)

        fun durationOf(durationMs: Long): Duration = durationMs.milliseconds
    }
}
