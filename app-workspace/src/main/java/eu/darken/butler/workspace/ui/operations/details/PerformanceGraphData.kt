package eu.darken.butler.workspace.ui.operations.details

import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
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
 *
 * [recentBytesPerSecond] and [recentItemsPerSecond] are averages over the raw history, not over the
 * decimated and smoothed series, so they stay the speeds the operation actually reported last.
 */
data class PerformanceGraphData(
    val progress: List<Float>,
    val byteSpeeds: List<Float>?,
    val itemSpeeds: List<Float>,
    val byteUnit: ByteSpeedUnit?,
    val maxByteSpeed: Double,
    val maxItemSpeed: Double,
    val recentBytesPerSecond: Long,
    val recentItemsPerSecond: Float,
) {

    companion object {
        private const val PROGRESS_STEP = 0.5f
        private const val SMOOTHING_WINDOW = 10

        fun from(history: PerformanceHistory): PerformanceGraphData? {
            if (!history.canShowGraph) return null
            // Without either total there is no x domain, every percentage would divide by zero
            if (history.totalBytes == 0L && history.totalItems == 0) return null

            // Plotting against accounted bytes needs a domain that actually moves, otherwise the
            // whole run collapses onto one x and nothing is plottable
            val useByteAxis = history.totalBytes > 0L &&
                history.samples.map { history.byteProgressOf(it) }.distinct().size >= 2

            val samples = mutableListOf<PerformanceSample>()
            val progress = mutableListOf<Float>()

            history.samples.forEach { sample ->
                val x = history.progressOf(sample, useByteAxis)
                if (progress.isEmpty() || x - progress.last() >= PROGRESS_STEP) {
                    samples.add(sample)
                    progress.add(x)
                }
            }

            // The final state matters even when it didn't advance enough to pass the filter
            val finalSample = history.samples.last()
            if (samples.last() !== finalSample) {
                val finalX = history.progressOf(finalSample, useByteAxis)
                when {
                    finalX == progress.last() -> {
                        samples[samples.lastIndex] = finalSample
                        progress[progress.lastIndex] = finalX
                    }
                    // Defensive: non-monotonic sample order (e.g. a wall-clock jump) can move the final sample back onto steps already plotted
                    finalX < progress.last() -> {
                        while (progress.isNotEmpty() && progress.last() >= finalX) {
                            samples.removeAt(samples.lastIndex)
                            progress.removeAt(progress.lastIndex)
                        }
                        samples.add(finalSample)
                        progress.add(finalX)
                    }
                    else -> {
                        samples.add(finalSample)
                        progress.add(finalX)
                    }
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
                recentBytesPerSecond = history.getRecentBytesPerSecond(),
                recentItemsPerSecond = history.getRecentItemsPerSecond(),
            )
        }

        /**
         * Share of the accounted work a sample carries, rounded to 0.5 steps.
         *
         * This is the axis candidate before the terminal clause in [progressOf], so it says whether
         * accounted bytes move at all.
         */
        private fun PerformanceHistory.byteProgressOf(sample: PerformanceSample): Float =
            roundToStep((sample.totalBytesAccounted.toFloat() / totalBytes.toFloat()) * 100f)

        /**
         * Completion percentage of a sample, rounded to 0.5 steps.
         *
         * On the byte axis a sample that has processed every item is pinned to 100%, so rounding
         * shortfalls and items that finish without accounting their full size still terminate.
         */
        private fun PerformanceHistory.progressOf(sample: PerformanceSample, useByteAxis: Boolean): Float {
            val raw = when {
                !useByteAxis -> if (totalItems > 0) {
                    (sample.totalItemsProcessed.toFloat() / totalItems.toFloat()) * 100f
                } else {
                    0f
                }

                totalItems > 0 && sample.totalItemsProcessed >= totalItems -> 100f
                else -> (sample.totalBytesAccounted.toFloat() / totalBytes.toFloat()) * 100f
            }
            return roundToStep(raw)
        }

        private fun roundToStep(percentage: Float): Float = round(percentage.coerceIn(0f, 100f) * 2) / 2f

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
