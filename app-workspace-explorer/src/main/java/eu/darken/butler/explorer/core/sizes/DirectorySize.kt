package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.common.files.APath
import kotlin.time.Instant

/** [bytes] includes any estimated missing contribution; [isComplete] describes actual traversal. */
data class DirectorySize(
    val bytes: Long,
    val isComplete: Boolean,
    val estimatedMissingBytes: Long? = null,
    val hasUnestimatedContent: Boolean = !isComplete,
) {
    val measuredBytes: Long get() = bytes - (estimatedMissingBytes ?: 0L)
    val isEstimated: Boolean get() = estimatedMissingBytes != null
}

/**
 * One completed walk of [root].
 *
 * [sizes] is keyed by [APath.path] and holds every directory the walk saw below [root], [root]
 * itself included.
 */
data class DirectoryScan(
    val root: APath<*>,
    val scannedAt: Instant,
    val sizes: Map<String, DirectorySize>,
    /** Entries the walk emitted. */
    val itemCount: Long,
    /** Failed locations plus entries the walk could not size. */
    val errorCount: Int,
    /** The first [DirectorySizeAggregator.MAX_PROBLEMS] of those, for display. */
    val problems: List<ScanProblem> = emptyList(),
    val estimate: AndroidDataEstimate? = null,
    val estimateFailure: AndroidDataEstimate.Failure? = null,
)

/** A location the walk could not read, or an entry it could not size. */
data class ScanProblem(
    val path: APath<*>,
    val message: String?,
)
