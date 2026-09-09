package eu.darken.butler.workspace.ui.operations.details

import com.patrykandpatrick.vico.core.cartesian.CartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.data.MutableCartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.data.toImmutable
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.MutableExtraStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

/**
 * Drives [plotGraphData] and [speedRangeProvider] the way [OperationPerformanceGraph] does while an
 * operation stalls: the ticker hands the chart a new x window while the sampler has recorded nothing
 * new, so the series in the transaction are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class PerformanceGraphRangeSlideTest : BaseTest() {

    private fun graphData(
        elapsedSeconds: List<Float>,
        byteSpeeds: List<Float>,
        itemSpeeds: List<Float>,
        xStart: Float,
        xEnd: Float,
    ) = PerformanceGraphData(
        elapsedSeconds = elapsedSeconds,
        byteSpeeds = byteSpeeds,
        itemSpeeds = itemSpeeds,
        byteUnit = ByteSpeedUnit.MB_S,
        maxByteSpeed = byteSpeeds.max().toDouble(),
        maxItemSpeed = itemSpeeds.max().toDouble(),
        recentBytesPerSecond = 12_000_000L,
        recentItemsPerSecond = itemSpeeds.last(),
        xStart = xStart,
        xEnd = xEnd,
        windowSpanSeconds = (xEnd - xStart).toInt(),
    )

    private fun layer(rangeMaxY: Double) = LineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.Line(LineCartesianLayer.LineFill.single(Fill(0xFF0000FF.toInt()))),
        ),
        rangeProvider = speedRangeProvider(rangeMaxY),
    )

    /** Layer order matches the series order [plotGraphData] writes: bytes first, then items. */
    private fun chartFor(graphData: PerformanceGraphData) = CartesianChart(
        layer(graphData.maxByteSpeed),
        layer(graphData.maxItemSpeed),
        getXStep = { 1.0 },
    )

    /** Mirrors `collectAsState`: one registration per chart id, always calling the newest chart. */
    private class Host(
        private val producer: CartesianChartModelProducer,
        initialChart: CartesianChart,
    ) {
        var chart: CartesianChart = initialChart
        val rangesFromChart = mutableListOf<Pair<Double, Double>>()
        val rangesAtHost = mutableListOf<Pair<Double, Double>>()

        val failures = mutableListOf<Throwable>()
        private val scope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> synchronized(failures) { failures += e } },
        )
        private val animationJobs = mutableListOf<Job>()
        private val hostExtraStore = MutableExtraStore()
        private val ranges = MutableCartesianChartRanges()

        suspend fun register() {
            producer.registerForUpdates(
                key = chart.id,
                cancelAnimation = {},
                startAnimation = { transformModel ->
                    synchronized(animationJobs) {
                        animationJobs += scope.launch { transformModel(chart.id, 1f) }
                    }
                },
                prepareForTransformation = { model, extraStore, chartRanges ->
                    chart.prepareForTransformation(model, extraStore, chartRanges)
                },
                transform = { extraStore, fraction -> chart.transform(extraStore, fraction) },
                hostExtraStore = hostExtraStore,
                updateRanges = { model ->
                    ranges.reset()
                    if (model != null) {
                        chart.updateRanges(ranges, model)
                        rangesFromChart += ranges.minX to ranges.maxX
                        ranges.toImmutable()
                    } else {
                        CartesianChartRanges.Empty
                    }
                },
                onUpdate = { _, chartRanges, _ ->
                    // `CartesianChartRanges.Empty` throws on every getter; the real host stores it as-is
                    if (chartRanges !== CartesianChartRanges.Empty) {
                        rangesAtHost += chartRanges.minX to chartRanges.maxX
                    }
                },
            )
            settle()
        }

        suspend fun settle() {
            val pending = synchronized(animationJobs) { animationJobs.toList().also { animationJobs.clear() } }
            pending.joinAll()
        }
    }

    private val elapsedSeconds = listOf(10f, 20f, 30f)
    private val byteSpeeds = listOf(4f, 6f, 5f)
    private val itemSpeeds = listOf(1f, 2f, 3f)

    @Test
    fun `a sliding x window with unchanged samples reaches the chart`() = runBlocking<Unit> {
        val first = graphData(elapsedSeconds, byteSpeeds, itemSpeeds, xStart = 0f, xEnd = 60f)
        val producer = CartesianChartModelProducer()
        val host = Host(producer, chartFor(first))
        host.register()

        producer.plotGraphData(first)
        host.settle()

        // The ticker fires: same samples, window moved on by 30 seconds
        val second = graphData(elapsedSeconds, byteSpeeds, itemSpeeds, xStart = 30f, xEnd = 90f)
        producer.plotGraphData(second)
        host.settle()

        host.failures shouldBe emptyList()
        host.rangesFromChart shouldBe listOf(0.0 to 60.0, 30.0 to 90.0)
        host.rangesAtHost.last() shouldBe (30.0 to 90.0)
    }

    /** Control: the same harness does observe a second update when the samples themselves change. */
    @Test
    fun `a new sample reaches the chart`() = runBlocking<Unit> {
        val first = graphData(elapsedSeconds, byteSpeeds, itemSpeeds, xStart = 0f, xEnd = 60f)
        val producer = CartesianChartModelProducer()
        val host = Host(producer, chartFor(first))
        host.register()

        producer.plotGraphData(first)
        host.settle()

        val second = graphData(
            elapsedSeconds = elapsedSeconds + 40f,
            byteSpeeds = byteSpeeds + 7f,
            itemSpeeds = itemSpeeds + 4f,
            xStart = 30f,
            xEnd = 90f,
        )
        producer.plotGraphData(second)
        host.settle()

        host.failures shouldBe emptyList()
        host.rangesFromChart shouldBe listOf(0.0 to 60.0, 30.0 to 90.0)
        host.rangesAtHost.last() shouldBe (30.0 to 90.0)
    }
}
