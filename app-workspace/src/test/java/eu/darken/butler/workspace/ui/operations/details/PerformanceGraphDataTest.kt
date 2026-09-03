package eu.darken.butler.workspace.ui.operations.details

import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Tests for [PerformanceGraphData] - turning a [PerformanceHistory] into plottable series.
 */
class PerformanceGraphDataTest : BaseTest() {

    private val startTime = Instant.fromEpochMilliseconds(1000)

    private fun sample(
        index: Int,
        bytesPerSecond: Long = 0L,
        itemsPerSecond: Float = 0f,
        totalBytesProcessed: Long = 0L,
        totalItemsProcessed: Int = 0,
    ) = PerformanceSample(
        timestamp = startTime + (index * 100).milliseconds,
        bytesPerSecond = bytesPerSecond,
        itemsPerSecond = itemsPerSecond,
        totalBytesProcessed = totalBytesProcessed,
        totalItemsProcessed = totalItemsProcessed,
    )

    private fun history(
        samples: List<PerformanceSample>,
        totalBytes: Long = 0L,
        totalItems: Int = 0,
    ) = PerformanceHistory(
        samples = samples,
        startTime = startTime,
        totalBytes = totalBytes,
        totalItems = totalItems,
    )

    // ============ BYTE UNIT SELECTION ============

    @Test
    fun `slow transfer is scaled into kilobytes per second`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(
                    index = i,
                    bytesPerSecond = 500_000L,
                    itemsPerSecond = 4f,
                    totalBytesProcessed = i * 500_000L,
                    totalItemsProcessed = i,
                )
            },
            totalBytes = 10_000_000L,
            totalItems = 20,
        )

        val data = PerformanceGraphData.from(history).shouldNotBeNull()

        data.byteUnit shouldBe ByteSpeedUnit.KB_S
        data.byteSpeeds.shouldNotBeNull().forEach { it shouldBe 500f }
        data.maxByteSpeed shouldBe 500.0
    }

    @Test
    fun `byte unit is selected from the fastest sample`() {
        fun unitFor(bytesPerSecond: Long): ByteSpeedUnit? {
            val history = history(
                samples = (0 until 20).map { i ->
                    sample(
                        index = i,
                        bytesPerSecond = bytesPerSecond,
                        itemsPerSecond = 1f,
                        totalBytesProcessed = i * 10L,
                        totalItemsProcessed = i,
                    )
                },
                totalItems = 20,
            )
            return PerformanceGraphData.from(history).shouldNotBeNull().byteUnit
        }

        unitFor(999L) shouldBe ByteSpeedUnit.B_S
        unitFor(1_000L) shouldBe ByteSpeedUnit.KB_S
        unitFor(999_999L) shouldBe ByteSpeedUnit.KB_S
        unitFor(1_000_000L) shouldBe ByteSpeedUnit.MB_S
        unitFor(999_999_999L) shouldBe ByteSpeedUnit.MB_S
        unitFor(1_000_000_000L) shouldBe ByteSpeedUnit.GB_S
    }

    @Test
    fun `bytes moved without a measured speed still get a byte series`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(
                    index = i,
                    bytesPerSecond = 0L,
                    itemsPerSecond = 2f,
                    totalBytesProcessed = i * 100_000L,
                    totalItemsProcessed = i,
                )
            },
            totalBytes = 2_000_000L,
            totalItems = 20,
        )

        val data = PerformanceGraphData.from(history).shouldNotBeNull()

        data.byteUnit shouldBe ByteSpeedUnit.B_S
        data.byteSpeeds.shouldNotBeNull().forEach { it shouldBe 0f }
        data.maxByteSpeed shouldBe 0.0
    }

    // ============ SERIES PRESENCE ============

    @Test
    fun `item only operation has no byte series`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(index = i, itemsPerSecond = 5f, totalItemsProcessed = i)
            },
            totalItems = 20,
        )

        val data = PerformanceGraphData.from(history).shouldNotBeNull()

        data.byteSpeeds shouldBe null
        data.byteUnit shouldBe null
        data.maxByteSpeed shouldBe 0.0
        data.progress shouldBe (0 until 20).map { it * 5f }
    }

    @Test
    fun `unknown size transfer keeps its byte series and plots against items`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(
                    index = i,
                    bytesPerSecond = 4_000_000L,
                    itemsPerSecond = 2f,
                    totalBytesProcessed = i * 4_000_000L,
                    totalItemsProcessed = i,
                )
            },
            totalBytes = 0L,  // Size unknown up front, e.g. a stream copy
            totalItems = 20,
        )

        val data = PerformanceGraphData.from(history).shouldNotBeNull()

        data.byteUnit shouldBe ByteSpeedUnit.MB_S
        data.byteSpeeds.shouldNotBeNull() shouldHaveSize 20
        data.progress shouldBe (0 until 20).map { it * 5f }
    }

    // ============ NO GRAPH ============

    @Test
    fun `history without totals has no x domain`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(index = i, bytesPerSecond = 1_000_000L, itemsPerSecond = 5f)
            },
        )

        PerformanceGraphData.from(history) shouldBe null
    }

    @Test
    fun `flat progress is not plottable`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(index = i, itemsPerSecond = 5f, totalItemsProcessed = 0)
            },
            totalItems = 100,
        )

        PerformanceGraphData.from(history) shouldBe null
    }

    @Test
    fun `too few samples produce no data`() {
        val history = history(
            samples = (0 until 9).map { i ->
                sample(index = i, itemsPerSecond = 5f, totalItemsProcessed = i)
            },
            totalItems = 9,
        )

        PerformanceGraphData.from(history) shouldBe null
    }

    // ============ X DOMAIN ============

    @Test
    fun `skipped items still let progress reach 100 percent`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(
                    index = i,
                    bytesPerSecond = 1_000L,
                    itemsPerSecond = 2f,
                    // Bytes stall at 10%: the remaining items were skipped
                    totalBytesProcessed = minOf(i, 2) * 50_000L,
                    totalItemsProcessed = i + 1,
                )
            },
            totalBytes = 1_000_000L,
            totalItems = 20,
        )

        val data = PerformanceGraphData.from(history).shouldNotBeNull()

        data.progress.last() shouldBe 100f
    }

    @Test
    fun `progress beyond the total is clamped to 100 percent`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(
                    index = i,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 2f,
                    // The last few samples report more than the total
                    totalBytesProcessed = (i + 1) * 100_000L,
                    totalItemsProcessed = i,
                )
            },
            totalBytes = 1_000_000L,
            totalItems = 20,
        )

        val data = PerformanceGraphData.from(history).shouldNotBeNull()

        data.progress.max() shouldBe 100f
        data.progress.last() shouldBe 100f
    }

    @Test
    fun `single large file still advances along the byte axis`() {
        val history = history(
            samples = (0 until 20).map { i ->
                sample(
                    index = i,
                    bytesPerSecond = 50_000_000L,
                    itemsPerSecond = 0.05f,
                    totalBytesProcessed = i * 50_000_000L,
                    totalItemsProcessed = 0,  // The single file is only counted once it is done
                )
            },
            totalBytes = 1_000_000_000L,
            totalItems = 1,
        )

        val data = PerformanceGraphData.from(history).shouldNotBeNull()

        data.progress shouldBe data.progress.distinct()
        data.progress.zipWithNext().forEach { (previous, next) -> (next > previous) shouldBe true }
    }

    // ============ FILTERING ============

    @Test
    fun `samples are kept once progress advanced half a percent`() {
        // 1 of 1000 items per sample, so most samples round onto the same 0.5% step
        val samples = (0 until 10).map { i ->
            sample(index = i, itemsPerSecond = (i + 1) * 10f, totalItemsProcessed = i + 1)
        }
        val data = PerformanceGraphData.from(history(samples, totalItems = 1000)).shouldNotBeNull()

        data.progress shouldBe listOf(0f, 0.5f, 1f)
        // The final sample replaced the entry that shared its 1.0% step: (10 + 30 + 100) / 3
        data.itemSpeeds.last() shouldBe (46.667f plusOrMinus 0.01f)
    }

    @Test
    fun `a final sample below earlier progress truncates back to it`() {
        val samples = (0 until 10).map { i ->
            sample(index = i, itemsPerSecond = 5f, totalItemsProcessed = i + 1)
        } + sample(index = 10, itemsPerSecond = 5f, totalItemsProcessed = 4)  // Progress reported lower

        val data = PerformanceGraphData.from(history(samples, totalItems = 10)).shouldNotBeNull()

        data.progress shouldBe listOf(10f, 20f, 30f, 40f)
        data.progress shouldBe data.progress.distinct()
    }

    @Test
    fun `a final sample above the last kept step is appended`() {
        // 1 of 1000 items per sample, so most samples round onto the same 0.5% step
        val samples = (0 until 11).map { i ->
            sample(index = i, itemsPerSecond = 5f, totalItemsProcessed = i + 1)
        } + sample(index = 11, itemsPerSecond = 5f, totalItemsProcessed = 100)  // A batch of items landed at once

        val data = PerformanceGraphData.from(history(samples, totalItems = 1000)).shouldNotBeNull()

        data.progress shouldBe listOf(0f, 0.5f, 1f, 10f)
        data.progress shouldBe data.progress.distinct()
        data.progress.zipWithNext().forEach { (previous, next) -> (next > previous) shouldBe true }
    }

    // ============ SMOOTHING ============

    @Test
    fun `smoothing only averages over preceding samples`() {
        val samples = (0 until 15).map { i ->
            sample(index = i, itemsPerSecond = (i + 1).toFloat(), totalItemsProcessed = i + 1)
        }

        val data = PerformanceGraphData.from(history(samples, totalItems = 15)).shouldNotBeNull()

        data.itemSpeeds shouldHaveSize 15
        // Partial window at the start: (1 + 2 + 3 + 4) / 4
        data.itemSpeeds[3] shouldBe (2.5f plusOrMinus 0.001f)
        // Full trailing window of 10: (4 + 5 + … + 13) / 10
        data.itemSpeeds[12] shouldBe (8.5f plusOrMinus 0.001f)
        // The last point knows nothing beyond itself: (6 + 7 + … + 15) / 10
        data.itemSpeeds.last() shouldBe (10.5f plusOrMinus 0.001f)
        data.maxItemSpeed shouldBe (10.5 plusOrMinus 0.001)
    }

    // ============ RECENT SPEEDS ============

    @Test
    fun `recent speeds average the raw samples`() {
        val samples = (0 until 20).map { i ->
            sample(
                index = i,
                bytesPerSecond = (i + 1) * 1_000_000L,
                itemsPerSecond = (i + 1).toFloat(),
                totalBytesProcessed = i * 1_000_000L,
                totalItemsProcessed = i,
            )
        }

        val data = PerformanceGraphData
            .from(history(samples, totalBytes = 20_000_000L, totalItems = 20))
            .shouldNotBeNull()

        // (1 + 2 + … + 20) / 20 = 10.5
        data.recentBytesPerSecond shouldBe 10_500_000L
        data.recentItemsPerSecond shouldBe (10.5f plusOrMinus 0.001f)
    }

    @Test
    fun `recent speeds only cover the last 30 samples`() {
        val samples = (0 until 40).map { i ->
            sample(
                index = i,
                bytesPerSecond = if (i < 10) 500_000_000L else 1_000_000L,
                itemsPerSecond = if (i < 10) 100f else 2f,
                totalBytesProcessed = i * 1_000_000L,
                totalItemsProcessed = i,
            )
        }

        val data = PerformanceGraphData
            .from(history(samples, totalBytes = 40_000_000L, totalItems = 40))
            .shouldNotBeNull()

        data.recentBytesPerSecond shouldBe 1_000_000L
        data.recentItemsPerSecond shouldBe (2f plusOrMinus 0.001f)
    }

    @Test
    fun `recent speeds ignore the filtering and smoothing of the series`() {
        // 1 of 1000 items per sample, so most samples round onto the same 0.5% step
        val samples = (0 until 10).map { i ->
            sample(index = i, itemsPerSecond = (i + 1) * 10f, totalItemsProcessed = i + 1)
        }

        val data = PerformanceGraphData.from(history(samples, totalItems = 1000)).shouldNotBeNull()

        data.itemSpeeds shouldHaveSize 3
        // (10 + 20 + … + 100) / 10, over every sample rather than the three plotted points
        data.recentItemsPerSecond shouldBe (55f plusOrMinus 0.001f)
    }
}
