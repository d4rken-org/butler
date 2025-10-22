package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.serialization.InstantSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Single performance sample captured at a point in time.
 */
@Serializable
data class PerformanceSample(
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    val bytesPerSecond: Long,
    val itemsPerSecond: Float,
    val totalBytesProcessed: Long,
    val totalItemsProcessed: Int,
)

/**
 * Historical performance data with adaptive sampling for memory efficiency.
 *
 * Sampling Strategy:
 * - Percentage-based bucketing: Divides 0-100% progress into 5% buckets (20 total)
 * - Maximum 50 samples per bucket (evenly distributed within bucket)
 * - Total maximum: 1000 samples distributed across entire operation range
 * - Supports both byte-based (copy/move) and item-based (delete) operations
 * - Uses totalBytes for percentage if available, otherwise uses totalItems
 * - Ensures graph coverage across full 0-100% even with rapid sampling (many small files)
 */
@Serializable
data class PerformanceHistory(
    val samples: List<PerformanceSample> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant? = null,
    val totalBytes: Long = 0L,
    val totalItems: Int = 0,
) {
    /**
     * Add a new sample with adaptive downsampling for old data.
     */
    fun addSample(sample: PerformanceSample, totalBytes: Long = 0L, totalItems: Int = 0): PerformanceHistory {
        log(
            TAG,
            DEBUG
        ) { "Adding sample. Current: ${samples.size} → New: ${samples.size + 1}, Speed: ${sample.bytesPerSecond / 1_000_000f} MB/s" }

        val updatedSamples = (samples + sample).let { allSamples ->
            if (allSamples.size <= MAX_SAMPLES) {
                allSamples
            } else {
                // Apply adaptive sampling: keep recent, downsample old
                log(TAG, DEBUG) { "Applying adaptive sampling" }
                adaptiveSample(allSamples)
            }
        }

        return copy(
            samples = updatedSamples,
            startTime = startTime ?: sample.timestamp,
            totalBytes = maxOf(this.totalBytes, totalBytes),
            totalItems = maxOf(this.totalItems, totalItems)
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
     * Get peak transfer speed.
     */
    val peakBytesPerSecond: Long
        get() = samples.maxOfOrNull { it.bytesPerSecond } ?: 0L

    /**
     * Total operation duration based on samples.
     */
    val duration: Duration?
        get() = if (samples.isEmpty() || startTime == null) {
            null
        } else {
            samples.last().timestamp - startTime
        }

    private fun adaptiveSample(allSamples: List<PerformanceSample>): List<PerformanceSample> {
        // Determine which metric to use for percentage calculation
        val useItems = totalBytes == 0L && totalItems > 0

        if (totalBytes == 0L && totalItems == 0) {
            // Can't determine percentage without either metric, fallback to keeping last N
            return allSamples.takeLast(MAX_SAMPLES)
        }

        // Percentage-based downsampling: ensure samples distributed across 0-100% range
        // Use 5% buckets (20 total: 0-19), with dynamic samples per bucket
        val bucketSize = 5.0
        val numBuckets = 20
        // Calculate samples per bucket to ensure total never exceeds MAX_SAMPLES
        val samplesPerBucket = (MAX_SAMPLES.toDouble() / numBuckets).toInt().coerceAtLeast(1)

        val buckets = allSamples.groupBy { sample ->
            val percentage = if (useItems) {
                (sample.totalItemsProcessed.toDouble() / totalItems) * 100.0
            } else {
                (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            }
            // Clamp percentage to [0.0, 100.0] and ensure bucket index is [0, 19]
            val clampedPercentage = percentage.coerceIn(0.0, 100.0)
            minOf(numBuckets - 1, (clampedPercentage / bucketSize).toInt())
        }

        // Downsample each bucket to max samplesPerBucket, keeping evenly distributed samples
        val downsampledSamples = buckets.flatMap { (_, samplesInBucket) ->
            if (samplesInBucket.size <= samplesPerBucket) {
                samplesInBucket
            } else {
                // Keep evenly distributed samples from this bucket
                val step = samplesInBucket.size.toDouble() / samplesPerBucket
                (0 until samplesPerBucket).map { i ->
                    samplesInBucket[(i * step).toInt()]
                }
            }
        }.sortedBy { it.timestamp }  // Maintain chronological order

        return downsampledSamples
    }

    override fun toString(): String {
        return "PerformanceHistory(startTime=$startTime, totalBytes=$totalBytes, totalItems=$totalItems, samples=${samples.size})"
    }

    companion object {
        private const val MAX_SAMPLES = 1000
        private val TAG = logTag("PerformanceHistory")
    }
}
