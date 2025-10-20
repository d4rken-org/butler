package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.serialization.InstantSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
 * - First 5 minutes: Keep all samples (250ms intervals = ~1200 samples)
 * - After 5 minutes: Downsample older data (keep every 4th sample)
 * - Maximum samples: ~1000 to prevent memory bloat on very long operations
 */
@Serializable
data class PerformanceHistory(
    val samples: List<PerformanceSample> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant? = null,
    val totalBytes: Long = 0L,
) {
    /**
     * Add a new sample with adaptive downsampling for old data.
     */
    fun addSample(sample: PerformanceSample, totalBytes: Long = 0L): PerformanceHistory {
        log(TAG, DEBUG) { "Adding sample. Current: ${samples.size} → New: ${samples.size + 1}, Speed: ${sample.bytesPerSecond / 1_000_000f} MB/s" }

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
            totalBytes = if (this.totalBytes == 0L) totalBytes else this.totalBytes
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
        val fiveMinutesAgo = allSamples.last().timestamp - 5.minutes

        val recentSamples = allSamples.filter { it.timestamp >= fiveMinutesAgo }
        val oldSamples = allSamples.filter { it.timestamp < fiveMinutesAgo }

        // Keep every 4th old sample to reduce memory
        val downsampledOld = oldSamples.filterIndexed { index, _ -> index % 4 == 0 }

        return (downsampledOld + recentSamples).takeLast(MAX_SAMPLES)
    }

    companion object {
        private const val MAX_SAMPLES = 1000
        private val TAG = logTag("PerformanceHistory")
    }
}
