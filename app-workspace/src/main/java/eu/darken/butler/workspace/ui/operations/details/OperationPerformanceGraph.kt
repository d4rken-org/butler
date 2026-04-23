package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import eu.darken.butler.common.formatByteSpeed
import eu.darken.butler.common.formatItemSpeed
import eu.darken.butler.workspace.R
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

private val TAG = logTag("PerformanceGraph")


@Composable
fun OperationPerformanceGraph(
    modifier: Modifier = Modifier,
    performanceHistory: PerformanceHistory,
) {
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)

    if (!performanceHistory.canShowGraph) {
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

    // State for Y-axis ranges (calculated from smoothed data)
    var minSpeed = remember { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
    var maxSpeed = remember { androidx.compose.runtime.mutableDoubleStateOf(100.0) }
    var minItems = remember { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
    var maxItems = remember { androidx.compose.runtime.mutableDoubleStateOf(10.0) }

    LaunchedEffect(performanceHistory.samples.size, performanceHistory.totalBytes) {
        log(
            TAG,
            DEBUG
        ) { "Rendering graph with ${performanceHistory.samples.size} samples, totalBytes: ${performanceHistory.totalBytes}" }

        // Filter samples to avoid Vico precision errors with many small files
        // Include samples where EITHER bytes% OR items% changed by at least 0.5%
        val filteredSamples = mutableListOf<PerformanceSample>()
        var lastBytesPercentage = -1f
        var lastItemsPercentage = -1f

        performanceHistory.samples.forEach { sample ->
            val bytesPercentage = if (performanceHistory.totalBytes > 0) {
                (sample.totalBytesProcessed.toFloat() / performanceHistory.totalBytes.toFloat()) * 100f
            } else {
                0f
            }

            val itemsPercentage = if (performanceHistory.totalItems > 0) {
                (sample.totalItemsProcessed.toFloat() / performanceHistory.totalItems.toFloat()) * 100f
            } else {
                0f
            }

            // Always include first sample, and samples where EITHER percentage changed by >= 0.5%
            val bytesChanged = (bytesPercentage - lastBytesPercentage) >= 0.5f
            val itemsChanged = (itemsPercentage - lastItemsPercentage) >= 0.5f

            if (filteredSamples.isEmpty() || bytesChanged || itemsChanged) {
                filteredSamples.add(sample)
                lastBytesPercentage = bytesPercentage
                lastItemsPercentage = itemsPercentage
            }
        }

        // Always include the last sample to show final state
        val lastSample = performanceHistory.samples.lastOrNull()
        if (lastSample != null && (filteredSamples.lastOrNull() != lastSample)) {
            filteredSamples.add(lastSample)
        }

        log(TAG, DEBUG) { "Filtered to ${filteredSamples.size} samples from ${performanceHistory.samples.size}" }

        // Map filtered samples to independent completion percentages (X-axes) and speeds (Y-axes)
        // Bytes completion percentage - used for bytes/s line
        val bytesCompletionPercentages = filteredSamples.map { sample ->
            val raw = if (performanceHistory.totalBytes > 0) {
                (sample.totalBytesProcessed.toFloat() / performanceHistory.totalBytes.toFloat()) * 100f
            } else {
                0f
            }
            // Round to nearest 0.5% to ensure clean GCD calculation (prevents Vico precision errors)
            (kotlin.math.round(raw * 2) / 2.0).toFloat()
        }

        // Items completion percentage - used for items/s line
        val itemsCompletionPercentages = filteredSamples.map { sample ->
            val raw = if (performanceHistory.totalItems > 0) {
                (sample.totalItemsProcessed.toFloat() / performanceHistory.totalItems.toFloat()) * 100f
            } else {
                0f
            }
            // Round to nearest 0.5% to ensure clean GCD calculation (prevents Vico precision errors)
            (kotlin.math.round(raw * 2) / 2.0).toFloat()
        }

        val bytesPerSecondMB = filteredSamples.map { sample ->
            sample.bytesPerSecond / 1_000_000f // Convert to MB/s
        }

        val itemsPerSecond = filteredSamples.map { sample ->
            sample.itemsPerSecond
        }

        // Apply moving average smoothing to reduce graph noise
        fun List<Number>.movingAverage(windowSize: Int = 10): List<Float> {
            if (size <= windowSize) return map { it.toFloat() }
            return indices.map { i ->
                val start = maxOf(0, i - windowSize / 2)
                val end = minOf(size, i + windowSize / 2 + 1)
                subList(start, end).sumOf { it.toDouble() }.toFloat() / (end - start)
            }
        }

        val smoothedBytesPerSecondMB = bytesPerSecondMB.movingAverage()
        val smoothedItemsPerSecond = itemsPerSecond.movingAverage()

        // Calculate Y-axis ranges from smoothed data for accurate axis scaling
        minSpeed.doubleValue = smoothedBytesPerSecondMB.minOrNull()?.toDouble() ?: 0.0
        maxSpeed.doubleValue = smoothedBytesPerSecondMB.maxOrNull()?.toDouble() ?: 100.0
        minItems.doubleValue = smoothedItemsPerSecond.minOrNull()?.toDouble() ?: 0.0
        maxItems.doubleValue = smoothedItemsPerSecond.maxOrNull()?.toDouble() ?: 10.0

        log(TAG, DEBUG) {
            "Smoothed bytes range: min=${"%.2f".format(minSpeed.doubleValue)} MB/s, max=${
                "%.2f".format(
                    maxSpeed.doubleValue
                )
            } MB/s"
        }
        log(
            TAG,
            DEBUG
        ) { "Smoothed items range: min=${"%.1f".format(minItems.doubleValue)} items/s, max=${"%.1f".format(maxItems.doubleValue)} items/s" }

        // Log filtered samples for debugging
        filteredSamples.take(3).forEachIndexed { idx, sample ->
            val pct = when {
                performanceHistory.totalBytes > 0 -> (sample.totalBytesProcessed.toFloat() / performanceHistory.totalBytes * 100f)
                performanceHistory.totalItems > 0 -> (sample.totalItemsProcessed.toFloat() / performanceHistory.totalItems * 100f)
                else -> 0f
            }
            log(
                TAG,
                DEBUG
            ) { "FilteredSample[$idx]: ${"%.1f".format(pct)}% complete, ${sample.bytesPerSecond / 1_000_000f} MB/s" }
        }
        if (filteredSamples.size > 3) {
            log(TAG, DEBUG) { "... (${filteredSamples.size - 6} filtered samples omitted) ..." }
            filteredSamples.takeLast(3).forEachIndexed { idx, sample ->
                val pct = when {
                    performanceHistory.totalBytes > 0 -> (sample.totalBytesProcessed.toFloat() / performanceHistory.totalBytes * 100f)
                    performanceHistory.totalItems > 0 -> (sample.totalItemsProcessed.toFloat() / performanceHistory.totalItems * 100f)
                    else -> 0f
                }
                val actualIdx = filteredSamples.size - 3 + idx
                log(
                    TAG,
                    DEBUG
                ) { "FilteredSample[$actualIdx]: ${"%.1f".format(pct)}% complete, ${sample.bytesPerSecond / 1_000_000f} MB/s" }
            }
        }

        modelProducer.runTransaction {
            // Each lineSeries block maps to a separate layer with independent X-axes
            lineSeries { series(x = bytesCompletionPercentages, y = smoothedBytesPerSecondMB) }  // Layer 0 (bytes)
            lineSeries { series(x = itemsCompletionPercentages, y = smoothedItemsPerSecond) }    // Layer 1 (items)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Chart with dual Y-axis overlay (bytes/second and items/second)
        Box {
            val bytesLineColor = MaterialTheme.colorScheme.primary
            val itemsLineColor = MaterialTheme.colorScheme.secondary

            val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
            val axisLabel = rememberAxisLabelComponent(
                color = axisLabelColor
            )

            CartesianChartHost(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(backgroundColor),
                chart = rememberCartesianChart(
                    // Layer 1: Bytes per second (left Y-axis) - series index 0
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(
                            LineCartesianLayer.rememberLine(
                                fill = remember(bytesLineColor) {
                                    LineCartesianLayer.LineFill.single(Fill(bytesLineColor.toArgb()))
                                },
                                areaFill = remember(bytesLineColor) {
                                    LineCartesianLayer.AreaFill.single(
                                        Fill(bytesLineColor.copy(alpha = 0.3f).toArgb())
                                    )
                                },
                                pointConnector = remember {
                                    LineCartesianLayer.PointConnector.cubic(curvature = 0.2f)
                                },
                            )
                        ),
                        rangeProvider = remember(minSpeed.doubleValue, maxSpeed.doubleValue) {
                            object : CartesianLayerRangeProvider {
                                override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                                    return 0.0
                                }

                                override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                                    return max(maxSpeed.doubleValue * 1.20, 5.0)
                                }

                                override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore): Double {
                                    return 0.0 // Fixed 0 to 100% range
                                }

                                override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore): Double {
                                    return 100.0 // Fixed 0 to 100% range
                                }
                            }
                        },
                        verticalAxisPosition = Axis.Position.Vertical.Start,
                    ),
                    // Layer 2: Items per second (right Y-axis) - series index 1
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(
                            LineCartesianLayer.rememberLine(
                                fill = remember(itemsLineColor) {
                                    LineCartesianLayer.LineFill.single(Fill(itemsLineColor.toArgb()))
                                },
                                areaFill = null,  // No area fill for items line to reduce clutter
                                pointConnector = remember {
                                    LineCartesianLayer.PointConnector.cubic(curvature = 0.2f)
                                },
                            )
                        ),
                        rangeProvider = remember(minItems.doubleValue, maxItems.doubleValue) {
                            object : CartesianLayerRangeProvider {
                                override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                                    return 0.0
                                }

                                override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                                    return max(maxItems.doubleValue * 1.20, 10.0)
                                }

                                override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore): Double {
                                    return 0.0 // Fixed 0 to 100% range
                                }

                                override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore): Double {
                                    return 100.0 // Fixed 0 to 100% range
                                }
                            }
                        },
                        verticalAxisPosition = Axis.Position.Vertical.End,
                    ),
                    startAxis = VerticalAxis.rememberStart(
                        label = axisLabel,
                        guideline = null,  // Hide grid lines
                        itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) },
                        valueFormatter = { _, value, _ -> value.toInt().toString() }
                    ),
                    endAxis = VerticalAxis.rememberEnd(
                        label = axisLabel,
                        guideline = null,  // Hide grid lines
                        itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) },
                        valueFormatter = { _, value, _ -> value.toInt().toString() }
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        label = null,  // Hide X-axis labels like Windows Explorer
                        guideline = null,  // Hide grid lines
                        tick = null,  // Hide tick marks
                    ),
                ),
                modelProducer = modelProducer,
                zoomState = rememberVicoZoomState(
                    zoomEnabled = false,
                    // Use content to fit all data without specifying exact range
                    initialZoom = remember { Zoom.Content },
                ),
            )

            // Recent average byte speed label (top-left, matches left Y-axis)
            if (performanceHistory.samples.isNotEmpty()) {
                val averageSpeed = performanceHistory.getRecentBytesPerSecond()

                Text(
                    text = formatByteSpeed(averageSpeed),
                    style = MaterialTheme.typography.labelSmall,
                    color = bytesLineColor,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopStart)
                        .offset(x = 48.dp, y = 16.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Recent average item speed label (top-right, matches right Y-axis)
            if (performanceHistory.samples.isNotEmpty()) {
                val averageItems = performanceHistory.getRecentItemsPerSecond().toInt()

                Text(
                    text = formatItemSpeed(averageItems.toDouble()),
                    style = MaterialTheme.typography.labelSmall,
                    color = itemsLineColor,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .offset(x = (-48).dp, y = 16.dp)
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationPerformanceGraphNoDataPreview() {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationPerformanceGraphHalfDataPreview() {
    // 50% completion with varying speeds (many small files scenario)
    val history = remember {
        val baseTime = kotlin.time.Clock.System.now()
        val totalBytes = 1_000_000_000L

        val samples = (0..50).map { i ->
            val progress = i / 100f
            val speed = (150_000_000 + (kotlin.math.sin(i * 0.3) * 100_000_000)).toLong()
            // Items/s varies independently - simulates many small files
            val items = (15.0 + (kotlin.math.sin(i * 0.5) * 10.0)).coerceAtLeast(5.0).toFloat()
            PerformanceSample(
                timestamp = baseTime + (i * 250).milliseconds,
                bytesPerSecond = speed.coerceAtLeast(50_000_000),
                itemsPerSecond = items,
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationPerformanceGraphFullDataPreview() {
    // 100% completion with varying speeds (mixed file sizes scenario)
    val history = remember {
        val baseTime = kotlin.time.Clock.System.now()
        val totalBytes = 1_000_000_000L

        val samples = (0..100).map { i ->
            val progress = i / 100f
            val speed = (200_000_000 + (kotlin.math.sin(i * 0.2) * 150_000_000)).toLong()
            // Items/s shows different pattern - starts high (small files), drops (large files), rises again
            val items = when {
                i < 30 -> (25.0 + (kotlin.math.sin(i * 0.4) * 8.0)).coerceAtLeast(10.0).toFloat()
                i < 70 -> (8.0 + (kotlin.math.sin(i * 0.3) * 3.0)).coerceAtLeast(3.0).toFloat()
                else -> (18.0 + (kotlin.math.sin(i * 0.5) * 7.0)).coerceAtLeast(8.0).toFloat()
            }
            PerformanceSample(
                timestamp = baseTime + (i * 250).milliseconds,
                bytesPerSecond = speed.coerceAtLeast(50_000_000),
                itemsPerSecond = items,
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
