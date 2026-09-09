package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.serialization.InstantSerializer
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Single performance sample captured at a point in time.
 *
 * [totalBytesProcessed] is bytes actually moved, [totalBytesAccounted] is work accounted for: those
 * bytes plus the size of every item that completed without moving one (a created directory, a
 * skipped file). The graph plots the latter, throughput is measured from the former.
 */
@Serializable
data class PerformanceSample(
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    val bytesPerSecond: Long,
    val itemsPerSecond: Float,
    val totalBytesProcessed: Long,
    val totalItemsProcessed: Int,
    val totalBytesAccounted: Long = totalBytesProcessed,
)

/**
 * Historical performance data with time-based retention for memory efficiency.
 *
 * Sampling Strategy:
 * - Hard cap: [MAX_SAMPLES] samples. Exceeding it triggers a compaction.
 * - The last [RECENT_WINDOW] is kept verbatim, so the live graph has every sample it plots.
 * - A forced-sampling burst inside that window is thinned to [RECENT_TARGET], leaving the newest
 *   [PROTECTED_TAIL] samples untouched for [getRecentBytesPerSecond] and [getRecentItemsPerSecond].
 * - Everything older is spread over [OVERVIEW_BUCKETS] equal-duration buckets, each thinned to
 *   [SAMPLES_PER_OVERVIEW_BUCKET] evenly spaced samples, endpoint-inclusive so bucket ends survive.
 * - Worst case retained is 741 samples, well under [COMPACT_TARGET], so compaction stays amortized.
 */
@Serializable
data class PerformanceHistory(
    val samples: List<PerformanceSample> = emptyList(),
    @Serializable(with = InstantSerializer::class) val startTime: Instant? = null,
    val totalBytes: Long = 0L,
    val totalItems: Int = 0,
) {
    /**
     * Get peak transfer speed.
     */
    val peakBytesPerSecond: Long
        get() = samples.maxOfOrNull { it.bytesPerSecond } ?: 0L

    /**
     * Whether this history has enough samples to display a meaningful graph.
     */
    val canShowGraph: Boolean
        get() = samples.size >= 10

    /**
     * Add a new sample, compacting older data once the cap is crossed.
     */
    fun addSample(sample: PerformanceSample, totalBytes: Long = 0L, totalItems: Int = 0): PerformanceHistory {
        log(
            TAG,
            DEBUG
        ) { "Adding sample. Current: ${samples.size} → New: ${samples.size + 1}, Speed: ${sample.bytesPerSecond / 1_000_000f} MB/s" }

        // The totals this add carries can grow during scanning
        val nextTotalBytes = maxOf(this.totalBytes, totalBytes)
        val nextTotalItems = maxOf(this.totalItems, totalItems)

        val updatedSamples = (samples + sample).let { allSamples ->
            if (allSamples.size <= MAX_SAMPLES) {
                allSamples
            } else {
                log(TAG, DEBUG) { "Compacting samples" }
                compact(allSamples)
            }
        }

        return copy(
            samples = updatedSamples,
            startTime = startTime ?: sample.timestamp,
            totalBytes = nextTotalBytes,
            totalItems = nextTotalItems,
        )
    }

    /**
     * Calculate average speed across all samples.
     */
    val averageBytesPerSecond: Long
        get() = if (samples.isEmpty()) 0L else samples.map { it.bytesPerSecond }.average().toLong()

    val averageItemsPerSecond: Float
        get() = if (samples.isEmpty()) 0f else samples.map { it.itemsPerSecond }.average().toFloat()

    /**
     * Calculate average speed over the most recent samples.
     *
     * @param sampleCount Number of recent samples to average (default 30)
     * @return Average bytes per second over recent samples, or 0 if no samples
     */
    fun getRecentBytesPerSecond(sampleCount: Int = 30): Long {
        if (samples.isEmpty()) return 0L
        val recentSamples = samples.takeLast(sampleCount)
        return recentSamples.map { it.bytesPerSecond }.average().toLong()
    }

    /**
     * Calculate average item processing speed over the most recent samples.
     *
     * @param sampleCount Number of recent samples to average (default 30)
     * @return Average items per second over recent samples, or 0 if no samples
     */
    fun getRecentItemsPerSecond(sampleCount: Int = 30): Float {
        if (samples.isEmpty()) return 0f
        val recentSamples = samples.takeLast(sampleCount)
        return recentSamples.map { it.itemsPerSecond }.average().toFloat()
    }

    /**
     * Total operation duration based on samples.
     */
    val duration: Duration?
        get() = if (samples.isEmpty() || startTime == null) {
            null
        } else {
            samples.last().timestamp - startTime
        }

    /**
     * Keep the last [RECENT_WINDOW] verbatim and thin everything older into an overview.
     */
    private fun compact(allSamples: List<PerformanceSample>): List<PerformanceSample> {
        // Recording order, not timestamps: the origin of the operation and the samples the progress
        // bar's speed and ETA read are defined by when they arrived, whatever the clock did to them
        // Matching on [startTime] relies on the sort below being stable: after a backwards clock jump
        // another sample can share the origin's timestamp, and only stability keeps the origin first
        val origin = allSamples.firstOrNull { it.timestamp == startTime } ?: allSamples.first()
        val protectedTail = allSamples.takeLast(PROTECTED_TAIL)

        // A clock that jumped backwards leaves the newest timestamp somewhere in the middle, and
        // every bound below reads an end of the list
        val ordered = allSamples.sortedBy { it.timestamp }
        val cutoff = ordered.last().timestamp - RECENT_WINDOW
        val compactable = ordered - protectedTail.toSet()
        val older = compactable.filter { it.timestamp < cutoff }
        val recent = compactable.filter { it.timestamp >= cutoff }

        // Only forced sampling can outpace the report interval enough to reach this
        val recentBudget = RECENT_TARGET - PROTECTED_TAIL
        val thinnedRecent = if (recent.size > recentBudget) {
            evenlySpaced(recent, recentBudget)
        } else {
            recent
        }

        val overview = if (older.isEmpty()) {
            emptyList()
        } else {
            val bucketStart = older.first().timestamp
            val span = older.last().timestamp - bucketStart
            older
                .groupBy { sample ->
                    if (span == Duration.ZERO) {
                        0
                    } else {
                        val position = (sample.timestamp - bucketStart) / span
                        (position * OVERVIEW_BUCKETS).toInt().coerceIn(0, OVERVIEW_BUCKETS - 1)
                    }
                }
                .flatMap { (_, samplesInBucket) -> evenlySpaced(samplesInBucket, SAMPLES_PER_OVERVIEW_BUCKET) }
        }

        return (listOf(origin) + overview + thinnedRecent + protectedTail).distinct().sortedBy { it.timestamp }
    }

    /**
     * Thin [source] to [target] entries, endpoint-inclusive so both ends survive.
     */
    private fun evenlySpaced(source: List<PerformanceSample>, target: Int): List<PerformanceSample> = when {
        source.size <= target -> source
        target <= 1 -> listOf(source.last())
        else -> {
            val lastIndex = source.size - 1
            (0 until target)
                .map { i -> ((i.toDouble() * lastIndex) / (target - 1)).roundToInt() }
                .distinct()
                .map { source[it] }
        }
    }

    override fun toString(): String {
        return "PerformanceHistory(startTime=$startTime, totalBytes=$totalBytes, totalItems=$totalItems, samples=${samples.size})"
    }

    companion object {
        internal const val MAX_SAMPLES = 1000
        internal const val COMPACT_TARGET = 800

        /** How much of the tail the live graph plots, and therefore may not be thinned. */
        internal val RECENT_WINDOW = 120.seconds
        internal const val RECENT_TARGET = 500

        /**
         * Twice the default window of [getRecentBytesPerSecond] and [getRecentItemsPerSecond], whose
         * results reach the user as the progress bar's speed and ETA.
         */
        internal const val PROTECTED_TAIL = 60
        internal const val OVERVIEW_BUCKETS = 60
        internal const val SAMPLES_PER_OVERVIEW_BUCKET = 4
        private val TAG = logTag("PerformanceHistory")
    }
}
