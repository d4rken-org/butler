package eu.darken.butler.common.files.local.operations.core

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Tracks progress for path operations (copy, move, delete).
 *
 * Maintains both overall operation progress (total items/bytes) and
 * current file progress for detailed progress reporting.
 *
 * @param progressReportInterval Minimum time between progress reports to avoid UI spam
 */
class PathOperationProgressTracker(
    private val progressReportInterval: Duration = 250.milliseconds
) {

    // Overall operation progress
    var totalItems = 0
    var itemsProcessed = 0
    var totalBytes = 0L
    var processedBytes = 0L

    // Current file progress
    var currentFileSize = 0L
        private set
    var currentFileBytes = 0L
        private set
    var currentFileStartTime: Instant? = null
        private set

    // Progress throttling
    private var lastProgressTime: Instant? = null

    /**
     * Starts tracking a new file.
     *
     * @param size Size of the file in bytes
     */
    fun startFile(size: Long) {
        currentFileSize = size
        currentFileBytes = 0L
        currentFileStartTime = Clock.System.now()
    }

    /**
     * Updates progress for the current file.
     *
     * @param bytes Number of bytes processed
     */
    fun updateFileProgress(bytes: Long) {
        currentFileBytes += bytes
        processedBytes += bytes
    }

    /**
     * Marks the current file as complete.
     */
    fun completeFile() {
        // Ensure we account for the full file size even if progress updates were missed
        val remaining = currentFileSize - currentFileBytes
        if (remaining > 0) {
            processedBytes += remaining
        }

        currentFileSize = 0L
        currentFileBytes = 0L
        currentFileStartTime = null
    }

    /**
     * Marks an item as complete without file-level tracking.
     * Use this for operations that don't track file-level progress (e.g., delete).
     */
    fun completeItem() {
        itemsProcessed++
    }

    /**
     * Marks an item as complete with its size.
     * Use this for operations that track bytes but not file-level progress.
     */
    fun completeItem(bytes: Long) {
        itemsProcessed++
        processedBytes += bytes
    }

    /**
     * Resets all progress tracking.
     * Should only be used if restarting the operation.
     */
    fun reset() {
        totalItems = 0
        itemsProcessed = 0
        totalBytes = 0L
        processedBytes = 0L
        currentFileSize = 0L
        currentFileBytes = 0L
        currentFileStartTime = null
        lastProgressTime = null
    }

    /**
     * Checks whether progress should be reported based on throttling interval.
     *
     * @param force If true, always returns true (for final progress reports)
     * @return true if enough time has passed since last report OR force is true
     */
    fun shouldReportProgress(force: Boolean = false): Boolean {
        if (force) {
            lastProgressTime = Clock.System.now()
            return true
        }

        val now = Clock.System.now()
        val lastTime = lastProgressTime

        return if (lastTime == null || (now - lastTime) >= progressReportInterval) {
            lastProgressTime = now
            true
        } else {
            false
        }
    }

    /**
     * Creates a snapshot of current progress.
     */
    fun createSnapshot(): ProgressSnapshot {
        return ProgressSnapshot(
            totalItems = totalItems,
            itemsProcessed = itemsProcessed,
            totalBytes = totalBytes,
            processedBytes = processedBytes,
            currentFileSize = currentFileSize,
            currentFileBytes = currentFileBytes,
            currentFileStartTime = currentFileStartTime
        )
    }

    /**
     * Immutable snapshot of progress at a point in time.
     */
    data class ProgressSnapshot(
        val totalItems: Int,
        val itemsProcessed: Int,
        val totalBytes: Long,
        val processedBytes: Long,
        val currentFileSize: Long,
        val currentFileBytes: Long,
        val currentFileStartTime: Instant?
    ) {
        /**
         * Calculates the percentage complete based on item count.
         */
        fun itemPercentage(): Float {
            return if (totalItems > 0) {
                (itemsProcessed.toFloat() / totalItems.toFloat()) * 100f
            } else 0f
        }

        /**
         * Calculates the percentage complete based on bytes.
         */
        fun bytePercentage(): Float {
            return if (totalBytes > 0) {
                (processedBytes.toFloat() / totalBytes.toFloat()) * 100f
            } else 0f
        }

        /**
         * Calculates the current file's percentage complete.
         */
        fun currentFilePercentage(): Float {
            return if (currentFileSize > 0) {
                (currentFileBytes.toFloat() / currentFileSize.toFloat()) * 100f
            } else 0f
        }
    }
}
