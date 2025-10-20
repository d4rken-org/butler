package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.background
import eu.darken.butler.workspace.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.formatSpeed
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import kotlin.time.Duration.Companion.milliseconds

private val TAG = logTag("PerformanceGraph")


@Composable
fun OperationPerformanceGraph(
    modifier: Modifier = Modifier,
    performanceHistory: PerformanceHistory,
) {
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)

    if (performanceHistory.samples.size < 10) {
        // Not enough data - show message
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.workspace_operation_performance_not_enough_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    // Calculate min/max speeds to set Y-axis range (prevents line from dropping to 0)
    val minSpeed = remember(performanceHistory.samples) {
        performanceHistory.samples.minOfOrNull { it.bytesPerSecond / 1_000_000.0 } ?: 0.0
    }
    val maxSpeed = remember(performanceHistory.samples) {
        performanceHistory.samples.maxOfOrNull { it.bytesPerSecond / 1_000_000.0 } ?: 100.0
    }

    LaunchedEffect(performanceHistory.samples.size, performanceHistory.totalBytes) {
        log(TAG, DEBUG) { "Rendering graph with ${performanceHistory.samples.size} samples, totalBytes: ${performanceHistory.totalBytes}" }

        // Filter samples to avoid Vico precision errors with many small files
        // Only include samples where percentage has changed by at least 0.5%
        val filteredSamples = mutableListOf<PerformanceSample>()
        var lastPercentage = -1f

        performanceHistory.samples.forEach { sample ->
            val percentage = if (performanceHistory.totalBytes > 0) {
                (sample.totalBytesProcessed.toFloat() / performanceHistory.totalBytes.toFloat()) * 100f
            } else {
                0f
            }

            // Always include first sample, and samples where percentage changed by >= 0.5%
            if (filteredSamples.isEmpty() || (percentage - lastPercentage) >= 0.5f) {
                filteredSamples.add(sample)
                lastPercentage = percentage
            }
        }

        // Always include the last sample to show final state
        val lastSample = performanceHistory.samples.lastOrNull()
        if (lastSample != null && (filteredSamples.lastOrNull() != lastSample)) {
            filteredSamples.add(lastSample)
        }

        log(TAG, DEBUG) { "Filtered to ${filteredSamples.size} samples from ${performanceHistory.samples.size}" }

        // Map filtered samples to completion percentage (X-axis) and speed in MB/s (Y-axis)
        val completionPercentages = filteredSamples.map { sample ->
            if (performanceHistory.totalBytes > 0) {
                val raw = (sample.totalBytesProcessed.toFloat() / performanceHistory.totalBytes.toFloat()) * 100f
                // Round to nearest 0.5% to ensure clean GCD calculation (prevents Vico precision errors)
                (kotlin.math.round(raw * 2) / 2.0).toFloat()
            } else {
                0f
            }
        }.distinct()  // Remove any duplicate values from rounding

        val bytesPerSecondMB = filteredSamples.map { sample ->
            sample.bytesPerSecond / 1_000_000f // Convert to MB/s
        }

        // Log filtered samples for debugging
        filteredSamples.take(3).forEachIndexed { idx, sample ->
            val pct = if (performanceHistory.totalBytes > 0) (sample.totalBytesProcessed.toFloat() / performanceHistory.totalBytes * 100f) else 0f
            log(TAG, DEBUG) { "FilteredSample[$idx]: ${"%.1f".format(pct)}% complete, ${sample.bytesPerSecond / 1_000_000f} MB/s" }
        }
        if (filteredSamples.size > 3) {
            log(TAG, DEBUG) { "... (${filteredSamples.size - 6} filtered samples omitted) ..." }
            filteredSamples.takeLast(3).forEachIndexed { idx, sample ->
                val pct = if (performanceHistory.totalBytes > 0) (sample.totalBytesProcessed.toFloat() / performanceHistory.totalBytes * 100f) else 0f
                val actualIdx = filteredSamples.size - 3 + idx
                log(TAG, DEBUG) { "FilteredSample[$actualIdx]: ${"%.1f".format(pct)}% complete, ${sample.bytesPerSecond / 1_000_000f} MB/s" }
            }
        }

        modelProducer.runTransaction {
            lineSeries {
                series(x = completionPercentages, y = bytesPerSecondMB)
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Chart with speed overlay
        Box {
            val lineColor = MaterialTheme.colorScheme.primary

            CartesianChartHost(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(backgroundColor),
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(
                            LineCartesianLayer.rememberLine(
                                fill = remember(lineColor) {
                                    LineCartesianLayer.LineFill.single(Fill(lineColor.toArgb()))
                                },
                                areaFill = remember(lineColor) {
                                    LineCartesianLayer.AreaFill.single(
                                        Fill(lineColor.copy(alpha = 0.3f).toArgb())
                                    )
                                },
                                pointConnector = remember {
                                    LineCartesianLayer.PointConnector.cubic(curvature = 0.5f)
                                },
                            )
                        ),
                        rangeProvider = remember(minSpeed, maxSpeed) {
                            object : CartesianLayerRangeProvider {
                                override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                                    return minSpeed * 0.8  // 20% padding below minimum
                                }
                                override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                                    return maxSpeed * 1.1  // 10% padding above maximum
                                }
                            }
                        }
                    ),
                    startAxis = VerticalAxis.rememberStart(
                        guideline = null  // Hide grid lines
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        label = null,  // Hide X-axis labels like Windows Explorer
                        guideline = null,  // Hide grid lines
                        tick = null  // Hide tick marks
                    ),
                ),
                modelProducer = modelProducer,
                zoomState = rememberVicoZoomState(
                    zoomEnabled = false,
                    // Use content to fit all data without specifying exact range
                    initialZoom = remember { Zoom.Content },
                ),
            )

            // Current speed label at last data point (Windows Explorer style)
            val lastSample = performanceHistory.samples.lastOrNull()
            if (lastSample != null) {
                val currentSpeed = formatSpeed(lastSample.bytesPerSecond)

                Text(
                    text = stringResource(R.string.workspace_operation_performance_speed_label, currentSpeed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomEnd)
                        .offset(x = (-16).dp, y = (-16).dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Preview2
@Composable
private fun OperationPerformanceGraphNoDataPreview() {
    PreviewWrapper {
        // Not enough samples - should not render
        val history = remember {
            val now = kotlin.time.Clock.System.now()
            PerformanceHistory(
                samples = listOf(
                    PerformanceSample(
                        timestamp = now,
                        bytesPerSecond = 150_000_000,
                        itemsPerSecond = 2.5f,
                        totalBytesProcessed = 50_000_000,
                        totalItemsProcessed = 5,
                    )
                ),
                startTime = now,
                totalBytes = 1_000_000_000,
            )
        }
        OperationPerformanceGraph(performanceHistory = history)
    }
}

@Preview2
@Composable
private fun OperationPerformanceGraphHalfDataPreview() {
    PreviewWrapper {
        // 50% completion with varying speeds
        val history = remember {
            val baseTime = kotlin.time.Clock.System.now()
            val totalBytes = 1_000_000_000L

            val samples = (0..50).map { i ->
                val progress = i / 100f
                val speed = (150_000_000 + (kotlin.math.sin(i * 0.3) * 100_000_000)).toLong()
                PerformanceSample(
                    timestamp = baseTime + (i * 250).milliseconds,
                    bytesPerSecond = speed.coerceAtLeast(50_000_000),
                    itemsPerSecond = (speed / 20_000_000f).coerceAtLeast(1f),
                    totalBytesProcessed = (totalBytes * progress).toLong(),
                    totalItemsProcessed = (progress * 1000).toInt(),
                )
            }

            PerformanceHistory(
                samples = samples,
                startTime = baseTime,
                totalBytes = totalBytes,
            )
        }
        OperationPerformanceGraph(performanceHistory = history)
    }
}

@Preview2
@Composable
private fun OperationPerformanceGraphFullDataPreview() {
    PreviewWrapper {
        // 100% completion with varying speeds
        val history = remember {
            val baseTime = kotlin.time.Clock.System.now()
            val totalBytes = 1_000_000_000L

            val samples = (0..100).map { i ->
                val progress = i / 100f
                val speed = (200_000_000 + (kotlin.math.sin(i * 0.2) * 150_000_000)).toLong()
                PerformanceSample(
                    timestamp = baseTime + (i * 250).milliseconds,
                    bytesPerSecond = speed.coerceAtLeast(50_000_000),
                    itemsPerSecond = (speed / 20_000_000f).coerceAtLeast(1f),
                    totalBytesProcessed = (totalBytes * progress).toLong(),
                    totalItemsProcessed = (progress * 1000).toInt(),
                )
            }

            PerformanceHistory(
                samples = samples,
                startTime = baseTime,
                totalBytes = totalBytes,
            )
        }
        OperationPerformanceGraph(performanceHistory = history)
    }
}
