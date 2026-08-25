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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import eu.darken.butler.common.formatByteSpeed
import eu.darken.butler.common.formatItemSpeed
import eu.darken.butler.workspace.R
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private val TAG = logTag("PerformanceGraph")


@Composable
fun OperationPerformanceGraph(
    modifier: Modifier = Modifier,
    performanceHistory: PerformanceHistory,
) {
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)

    val graphData = remember(performanceHistory) { PerformanceGraphData.from(performanceHistory) }

    if (graphData == null) {
        // Not enough data - show message
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.workspace_operation_performance_not_enough_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val byteSpeeds = graphData.byteSpeeds
    // Keyed by mode so a two series model can't be handed to a single layer chart
    val modelProducer = remember(byteSpeeds != null) { CartesianChartModelProducer() }

    LaunchedEffect(graphData) {
        log(TAG, DEBUG) {
            "Plotting ${graphData.progress.size} points from ${performanceHistory.samples.size} samples"
        }
        modelProducer.runTransaction {
            if (byteSpeeds != null) lineSeries { series(x = graphData.progress, y = byteSpeeds) }
            lineSeries { series(x = graphData.progress, y = graphData.itemSpeeds) }
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

            val axisLabel = rememberAxisLabelComponent(color = MaterialTheme.colorScheme.onSurfaceVariant)

            val maxBytes = axisMax(graphData.maxByteSpeed)
            val maxItems = axisMax(graphData.maxItemSpeed)

            val bytesLayer = if (byteSpeeds != null) {
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
                    rangeProvider = remember(maxBytes) { speedRangeProvider(maxBytes) },
                    verticalAxisPosition = Axis.Position.Vertical.Start,
                )
            } else {
                null
            }

            val itemsLayer = rememberLineCartesianLayer(
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
                rangeProvider = remember(maxItems) { speedRangeProvider(maxItems) },
                verticalAxisPosition = if (byteSpeeds != null) {
                    Axis.Position.Vertical.End
                } else {
                    Axis.Position.Vertical.Start
                },
            )

            val itemsUnitLabel = stringResource(R.string.workspace_operation_performance_unit_items)
            val bytesUnitLabel = graphData.byteUnit?.let { stringResource(it.labelRes) }

            // Without byte data the single start axis belongs to the items layer
            val startAxis = VerticalAxis.rememberStart(
                label = axisLabel,
                guideline = null,  // Hide grid lines
                itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) },
                valueFormatter = remember(maxBytes, maxItems, byteSpeeds != null) {
                    speedValueFormatter(if (byteSpeeds != null) maxBytes else maxItems)
                },
                title = bytesUnitLabel ?: itemsUnitLabel,
                titleComponent = rememberAxisLabelComponent(
                    color = if (byteSpeeds != null) bytesLineColor else itemsLineColor,
                ),
            )
            val endAxis = if (byteSpeeds != null) {
                VerticalAxis.rememberEnd(
                    label = axisLabel,
                    guideline = null,  // Hide grid lines
                    itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) },
                    valueFormatter = remember(maxItems) { speedValueFormatter(maxItems) },
                    title = itemsUnitLabel,
                    titleComponent = rememberAxisLabelComponent(color = itemsLineColor),
                )
            } else {
                null
            }

            CartesianChartHost(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(backgroundColor),
                chart = rememberCartesianChart(
                    // Layer order matches the series order in the transaction above
                    *listOfNotNull(bytesLayer, itemsLayer).toTypedArray(),
                    startAxis = startAxis,
                    endAxis = endAxis,
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
            if (byteSpeeds != null) {
                SpeedChip(
                    text = formatByteSpeed(performanceHistory.getRecentBytesPerSecond()),
                    color = bytesLineColor,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 48.dp, y = 16.dp),
                )
            }

            // Recent average item speed label (top-right, matches right Y-axis)
            SpeedChip(
                text = formatItemSpeed(performanceHistory.getRecentItemsPerSecond().toDouble()),
                color = itemsLineColor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-48).dp, y = 16.dp),
            )
        }
    }
}

@Composable
private fun SpeedChip(
    modifier: Modifier = Modifier,
    text: String,
    color: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

private val ByteSpeedUnit.labelRes: Int
    get() = when (this) {
        ByteSpeedUnit.B_S -> R.string.workspace_operation_performance_unit_bytes
        ByteSpeedUnit.KB_S -> R.string.workspace_operation_performance_unit_kilobytes
        ByteSpeedUnit.MB_S -> R.string.workspace_operation_performance_unit_megabytes
        ByteSpeedUnit.GB_S -> R.string.workspace_operation_performance_unit_gigabytes
    }

private fun axisMax(maxValue: Double): Double = if (maxValue == 0.0) 1.0 else maxValue * 1.20

private fun speedRangeProvider(rangeMaxY: Double) = object : CartesianLayerRangeProvider {
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double = 0.0

    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double = rangeMaxY

    override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore): Double = 0.0

    override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore): Double = 100.0
}

/** Slow axes need decimals, fast ones would only repeat the same rounded label. */
private fun speedValueFormatter(maxY: Double) = CartesianValueFormatter { _, value, _ ->
    when {
        maxY >= 10.0 -> String.format(Locale.getDefault(), "%.0f", value)
        maxY >= 1.0 -> String.format(Locale.getDefault(), "%.1f", value)
        else -> String.format(Locale.getDefault(), "%.2f", value)
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
            totalItems = 1000,
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
            totalItems = 1000,
        )
    }
    OperationPerformanceGraph(performanceHistory = history)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationPerformanceGraphItemsOnlyPreview() {
    // Deleting 500 empty files: no bytes anywhere, only the items axis has data
    val history = remember {
        val baseTime = kotlin.time.Clock.System.now()
        val totalItems = 500

        val samples = (0..100).map { i ->
            val items = (40.0 + (kotlin.math.sin(i * 0.35) * 15.0)).coerceAtLeast(10.0).toFloat()
            PerformanceSample(
                timestamp = baseTime + (i * 250).milliseconds,
                bytesPerSecond = 0L,
                itemsPerSecond = items,
                totalBytesProcessed = 0L,
                totalItemsProcessed = (totalItems * (i / 100f)).toInt(),
            )
        }

        PerformanceHistory(
            samples = samples,
            startTime = baseTime,
            totalItems = totalItems,
        )
    }
    OperationPerformanceGraph(performanceHistory = history)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationPerformanceGraphSingleFilePreview() {
    // A single large file: the item count never moves, item speed stays below 1/s
    val history = remember {
        val baseTime = kotlin.time.Clock.System.now()
        val totalBytes = 4_000_000_000L

        val samples = (0..100).map { i ->
            val progress = i / 100f
            val speed = (80_000_000 + (kotlin.math.sin(i * 0.25) * 20_000_000)).toLong()
            PerformanceSample(
                timestamp = baseTime + (i * 500).milliseconds,
                bytesPerSecond = speed,
                itemsPerSecond = 0.02f,
                totalBytesProcessed = (totalBytes * progress).toLong(),
                totalItemsProcessed = 0,
            )
        }

        PerformanceHistory(
            samples = samples,
            startTime = baseTime,
            totalBytes = totalBytes,
            totalItems = 1,
        )
    }
    OperationPerformanceGraph(performanceHistory = history)
}
