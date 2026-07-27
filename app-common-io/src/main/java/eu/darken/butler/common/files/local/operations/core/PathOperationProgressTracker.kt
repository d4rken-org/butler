package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
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
 * @param clock Time source for throttling decisions, overridable so tests can control it
 */
class PathOperationProgressTracker(
    private val progressReportInterval: Duration = 250.milliseconds,
    private val clock: Clock = Clock.System,
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

    // Performance tracking
    var performanceHistory = PerformanceHistory()
        private set

    private var lastSampleTime: Instant? = null
    private var lastSampleBytes = 0L
    private var lastSampleItems = 0

    /**
     * Starts tracking a new file.
     *
     * @param size Size of the file in bytes
     */
    fun startFile(size: Long) {
        currentFileSize = size
        currentFileBytes = 0L
        currentFileStartTime = clock.now()
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
        performanceHistory = PerformanceHistory()
        lastSampleTime = null
        lastSampleBytes = 0L
        lastSampleItems = 0
    }

    /**
     * Checks whether progress should be reported based on throttling interval.
     *
     * @param force If true, always returns true (for final progress reports)
     * @return true if enough time has passed since last report OR force is true
     */
    fun shouldReportProgress(force: Boolean = false): Boolean {
        if (force) {
            val now = clock.now()
            recordPerformanceSample(now)
            log(TAG, DEBUG) { "Progress report (forced). Samples: ${performanceHistory.samples.size}" }
            lastProgressTime = now
            return true
        }

        val now = clock.now()
        val lastTime = lastProgressTime

        return if (lastTime == null || (now - lastTime) >= progressReportInterval) {
            recordPerformanceSample(now)
            log(TAG, DEBUG) { "Progress report. Samples: ${performanceHistory.samples.size}" }
            lastProgressTime = now
            true
        } else {
            false
        }
    }

    companion object {
        private val TAG = logTag("ProgressTracker")
    }

    /**
     * Records a performance sample based on progress since last sample.
     * Always records samples when progress has been made, even with zero time delta.
     */
    private fun recordPerformanceSample(now: Instant) {
        val lastTime = lastSampleTime
        val lastBytes = lastSampleBytes
        val lastItems = lastSampleItems

        // Calculate deltas
        val bytesDelta = processedBytes - lastBytes
        val itemsDelta = itemsProcessed - lastItems

        // Only skip if no progress has been made
        if (bytesDelta == 0L && itemsDelta == 0) {
            lastSampleTime = now
            return
        }

        // Calculate speeds
        val bytesPerSecond: Long
        val itemsPerSecond: Float

        if (lastTime != null) {
            val timeDelta = (now - lastTime).inWholeMilliseconds / 1000.0

            if (timeDelta > 0) {
                // Normal case: calculate from delta
                bytesPerSecond = (bytesDelta / timeDelta).toLong()
                itemsPerSecond = (itemsDelta / timeDelta).toFloat()
            } else {
                // Zero time delta (< 1ms) - estimate from total progress
                val startTime = performanceHistory.startTime
                val totalTime = if (startTime != null) {
                    (now - startTime).inWholeMilliseconds / 1000.0
                } else {
                    0.0
                }

                if (totalTime > 0) {
                    bytesPerSecond = (processedBytes / totalTime).toLong()
                    itemsPerSecond = (itemsProcessed / totalTime).toFloat()
                } else {
                    // Fallback: use previous sample's speed or 0
                    bytesPerSecond = performanceHistory.samples.lastOrNull()?.bytesPerSecond ?: 0L
                    itemsPerSecond = performanceHistory.samples.lastOrNull()?.itemsPerSecond ?: 0f
                }
            }
        } else {
            // First sample: estimate from total progress so far
            val startTime = performanceHistory.startTime
            val totalTime = if (startTime != null) {
                (now - startTime).inWholeMilliseconds / 1000.0
            } else {
                0.0
            }

            if (totalTime > 0) {
                bytesPerSecond = (processedBytes / totalTime).toLong()
                itemsPerSecond = (itemsProcessed / totalTime).toFloat()
            } else {
                // Very first sample with no time - use 0
                bytesPerSecond = 0L
                itemsPerSecond = 0f
            }
        }

        val sample = PerformanceSample(
            timestamp = now,
            bytesPerSecond = bytesPerSecond,
            itemsPerSecond = itemsPerSecond,
            totalBytesProcessed = processedBytes,
            totalItemsProcessed = itemsProcessed,
        )

        performanceHistory = performanceHistory.addSample(
            sample,
            totalBytes = this.totalBytes,
            totalItems = this.totalItems
        )

        lastSampleTime = now
        lastSampleBytes = processedBytes
        lastSampleItems = itemsProcessed
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
    )
}
