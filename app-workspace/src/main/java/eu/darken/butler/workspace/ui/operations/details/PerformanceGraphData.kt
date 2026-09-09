package eu.darken.butler.workspace.ui.operations.details

import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

enum class ByteSpeedUnit(val divisor: Double) {
    B_S(1.0),
    KB_S(1_000.0),
    MB_S(1_000_000.0),
    GB_S(1_000_000_000.0),
}

/**
 * Which stretch of an operation the graph covers.
 *
 * [LIVE] follows the most recent [PerformanceGraphData.RECENT_WINDOW_SECONDS], [OVERVIEW] spans the
 * whole sampled transfer.
 */
enum class PerformanceGraphScope {
    LIVE,
    OVERVIEW,
}

/**
 * Plot-ready series for [OperationPerformanceGraph].
 *
 * All series share the [elapsedSeconds] x values, which are seconds since the first recorded sample
 * rounded to 0.5 s steps. [xStart] and [xEnd] are the axis bounds, and may reach past the newest
 * sample while an operation stalls.
 *
 * [windowSpanSeconds] is the length of the live window, null for a completed operation.
 *
 * [recentBytesPerSecond] and [recentItemsPerSecond] are averages over the raw history, not over the
 * decimated and smoothed series, so they stay the speeds the operation actually reported last.
 */
data class PerformanceGraphData(
    val elapsedSeconds: List<Float>,
    val byteSpeeds: List<Float>?,
    val itemSpeeds: List<Float>,
    val byteUnit: ByteSpeedUnit?,
    val maxByteSpeed: Double,
    val maxItemSpeed: Double,
    val recentBytesPerSecond: Long,
    val recentItemsPerSecond: Float,
    val xStart: Float,
    val xEnd: Float,
    val windowSpanSeconds: Int?,
) {

    companion object {
        /** Read by [OperationPerformanceGraph] as the floor for the chart's x step. */
        internal const val PLOT_STEP_SECONDS = 0.5f
        internal const val RECENT_WINDOW_SECONDS = 120f
        private val SMOOTHING_DURATION = 2.seconds

        fun from(
            history: PerformanceHistory,
            scope: PerformanceGraphScope,
            now: Instant,
        ): PerformanceGraphData? {
            if (!history.canShowGraph) return null

            val samples = history.samples
            // The axis spans the sampled transfer: nothing is recorded until progress moves, so an
            // operation's own start would prepend its scanning phase as blank space
            val origin = history.startTime ?: samples.first().timestamp

            val elapsed = mutableListOf<Float>()
            samples.forEach { sample ->
                val x = roundToStep((sample.timestamp - origin).inWholeMilliseconds / 1000f).coerceAtLeast(0f)
                // A wall-clock jump backwards must not delete curve that was already measured
                elapsed.add(if (elapsed.isEmpty()) x else maxOf(x, elapsed.last()))
            }

            val smoothedBytes = samples.trailingAverage { it.bytesPerSecond.toDouble() }
            val smoothedItems = samples.trailingAverage { it.itemsPerSecond.toDouble() }

            val lastX = elapsed.last()
            val xEnd = when (scope) {
                PerformanceGraphScope.LIVE -> ((now - origin).inWholeMilliseconds / 1000f).coerceAtLeast(lastX)
                PerformanceGraphScope.OVERVIEW -> lastX
            }
            val xStart = when (scope) {
                PerformanceGraphScope.LIVE -> (xEnd - RECENT_WINDOW_SECONDS).coerceAtLeast(0f)
                PerformanceGraphScope.OVERVIEW -> 0f
            }
            val windowSpanSeconds = when (scope) {
                PerformanceGraphScope.LIVE -> (xEnd - xStart).roundToInt()
                PerformanceGraphScope.OVERVIEW -> null
            }

            // Selecting over the whole history first makes the x values distinct by construction, so
            // cropping them cannot merge the two points the chart needs into one plot step
            val selected = mutableListOf<Int>()
            for (i in elapsed.indices) {
                if (selected.isEmpty() || elapsed[i] > elapsed[selected.last()]) selected.add(i)
            }

            // The final state matters even when it didn't advance enough to pass the filter
            if (selected.last() != elapsed.lastIndex) selected[selected.lastIndex] = elapsed.lastIndex

            val cropStart = when (scope) {
                PerformanceGraphScope.LIVE -> {
                    // One point before the window, so the line enters from the left edge
                    val firstInWindow = selected.indexOfFirst { elapsed[it] >= xStart }
                    val cropped = if (firstInWindow < 0) selected.size else (firstInWindow - 1).coerceAtLeast(0)
                    // A stall longer than the window pushes every sample out of it, the chart stays
                    if (selected.lastIndex - cropped >= 1) cropped else (selected.lastIndex - 1).coerceAtLeast(0)
                }

                PerformanceGraphScope.OVERVIEW -> 0
            }

            val plotted = selected.drop(cropStart)
            val xValues = plotted.map { elapsed[it] }

            if (xValues.size < 2) return null

            val hasByteData = samples.any { it.bytesPerSecond > 0L || it.totalBytesProcessed > 0L }
            // The y range comes from the plotted points, so the unit has to follow the same population
            val byteUnit = if (hasByteData) unitFor(plotted.maxOf { samples[it].bytesPerSecond }) else null

            val byteSpeeds = byteUnit?.let { unit -> plotted.map { (smoothedBytes[it] / unit.divisor).toFloat() } }
            val itemSpeeds = plotted.map { smoothedItems[it].toFloat() }

            return PerformanceGraphData(
                elapsedSeconds = xValues,
                byteSpeeds = byteSpeeds,
                itemSpeeds = itemSpeeds,
                byteUnit = byteUnit,
                maxByteSpeed = byteSpeeds?.max()?.toDouble() ?: 0.0,
                maxItemSpeed = itemSpeeds.max().toDouble(),
                recentBytesPerSecond = history.getRecentBytesPerSecond(),
                recentItemsPerSecond = history.getRecentItemsPerSecond(),
                xStart = xStart,
                xEnd = xEnd,
                windowSpanSeconds = windowSpanSeconds,
            )
        }

        private fun roundToStep(seconds: Float): Float = round(seconds / PLOT_STEP_SECONDS) * PLOT_STEP_SECONDS

        private fun unitFor(maxBytesPerSecond: Long): ByteSpeedUnit = when {
            maxBytesPerSecond < 1_000L -> ByteSpeedUnit.B_S
            maxBytesPerSecond < 1_000_000L -> ByteSpeedUnit.KB_S
            maxBytesPerSecond < 1_000_000_000L -> ByteSpeedUnit.MB_S
            else -> ByteSpeedUnit.GB_S
        }

        /**
         * Moving average over the samples within [SMOOTHING_DURATION] before each one.
         *
         * Trailing, not centered: a point may never be smoothed by values that come after it. The
         * index guard matters as much as the timestamp one, two samples can share a timestamp and a
         * later one must not change what an earlier one already plotted.
         */
        private fun List<PerformanceSample>.trailingAverage(value: (PerformanceSample) -> Double): List<Double> =
            indices.map { i ->
                val windowStart = this[i].timestamp - SMOOTHING_DURATION
                var sum = 0.0
                var count = 0
                for (j in i downTo 0) {
                    if (this[j].timestamp <= windowStart) continue
                    sum += value(this[j])
                    count++
                }
                sum / count
            }
    }
}
