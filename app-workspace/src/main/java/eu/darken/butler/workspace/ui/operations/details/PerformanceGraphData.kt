package eu.darken.butler.workspace.ui.operations.details

import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import kotlin.math.max
import kotlin.math.round

enum class ByteSpeedUnit(val divisor: Double) {
    B_S(1.0),
    KB_S(1_000.0),
    MB_S(1_000_000.0),
    GB_S(1_000_000_000.0),
}

/**
 * Plot-ready series for [OperationPerformanceGraph].
 *
 * All series share the [progress] x values, which are completion percentages rounded to 0.5 steps.
 */
data class PerformanceGraphData(
    val progress: List<Float>,
    val byteSpeeds: List<Float>?,
    val itemSpeeds: List<Float>,
    val byteUnit: ByteSpeedUnit?,
    val maxByteSpeed: Double,
    val maxItemSpeed: Double,
) {

    companion object {
        private const val PROGRESS_STEP = 0.5f
        private const val SMOOTHING_WINDOW = 10

        fun from(history: PerformanceHistory): PerformanceGraphData? {
            if (!history.canShowGraph) return null
            // Without either total there is no x domain, every percentage would divide by zero
            if (history.totalBytes == 0L && history.totalItems == 0) return null

            val samples = mutableListOf<PerformanceSample>()
            val progress = mutableListOf<Float>()

            history.samples.forEach { sample ->
                val x = history.progressOf(sample)
                if (progress.isEmpty() || x - progress.last() >= PROGRESS_STEP) {
                    samples.add(sample)
                    progress.add(x)
                }
            }

            // The final state matters even when it didn't advance enough to pass the filter
            val finalSample = history.samples.last()
            if (samples.last() !== finalSample) {
                val finalX = history.progressOf(finalSample)
                if (finalX == progress.last()) {
                    samples[samples.lastIndex] = finalSample
                    progress[progress.lastIndex] = finalX
                } else {
                    samples.add(finalSample)
                    progress.add(finalX)
                }
            }

            // A flat x domain (e.g. an all-skipped operation) has nothing to plot against
            if (progress.distinct().size < 2) return null

            val hasByteData = history.samples.any { it.bytesPerSecond > 0L || it.totalBytesProcessed > 0L }
            val byteUnit = if (hasByteData) unitFor(samples.maxOf { it.bytesPerSecond }) else null

            val byteSpeeds = byteUnit?.let { unit ->
                samples.map { it.bytesPerSecond / unit.divisor }.trailingAverage()
            }
            val itemSpeeds = samples.map { it.itemsPerSecond.toDouble() }.trailingAverage()

            return PerformanceGraphData(
                progress = progress,
                byteSpeeds = byteSpeeds,
                itemSpeeds = itemSpeeds,
                byteUnit = byteUnit,
                maxByteSpeed = byteSpeeds?.max()?.toDouble() ?: 0.0,
                maxItemSpeed = itemSpeeds.max().toDouble(),
            )
        }

        /**
         * Completion percentage of a sample, rounded to 0.5 steps.
         *
         * Bytes and items are unified via max() so that operations where one metric stalls, e.g. a
         * copy that skips items, still reach 100%.
         */
        private fun PerformanceHistory.progressOf(sample: PerformanceSample): Float {
            val bytesPercentage = if (totalBytes > 0L) {
                (sample.totalBytesProcessed.toFloat() / totalBytes.toFloat()) * 100f
            } else {
                0f
            }
            val itemsPercentage = if (totalItems > 0) {
                (sample.totalItemsProcessed.toFloat() / totalItems.toFloat()) * 100f
            } else {
                0f
            }
            val raw = max(bytesPercentage.coerceIn(0f, 100f), itemsPercentage.coerceIn(0f, 100f))
            return round(raw * 2) / 2f
        }

        private fun unitFor(maxBytesPerSecond: Long): ByteSpeedUnit = when {
            maxBytesPerSecond < 1_000L -> ByteSpeedUnit.B_S
            maxBytesPerSecond < 1_000_000L -> ByteSpeedUnit.KB_S
            maxBytesPerSecond < 1_000_000_000L -> ByteSpeedUnit.MB_S
            else -> ByteSpeedUnit.GB_S
        }

        /**
         * Moving average over the current and the previous [window] - 1 values.
         *
         * Trailing, not centered: a point may never be smoothed by values that come after it.
         */
        private fun List<Double>.trailingAverage(window: Int = SMOOTHING_WINDOW): List<Float> = indices.map { i ->
            val start = maxOf(0, i - window + 1)
            var sum = 0.0
            for (j in start..i) sum += this[j]
            (sum / (i - start + 1)).toFloat()
        }
    }
}
