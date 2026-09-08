package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.storage.ExternalStorageStatsProvider
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AndroidDataSizeEstimatorTest : BaseTest() {
    private val root = LocalPath.build("/storage/emulated/10")
    private val time = Instant.fromEpochMilliseconds(1234)
    private val target = ExternalStorageStatsProvider.Target(root, Uuid.NIL, UserHandle2(10))
    private val estimator = AndroidDataSizeEstimator(root)
    private val aggregator = DirectorySizeAggregator(root, estimator)

    private fun entry(name: String, allocation: Long?, size: Long? = null) = LocalPathLookup(
        lookedUp = if (name.isEmpty()) root else root.child(*name.split('/').toTypedArray()),
        fileType = if (size == null) FileType.DIRECTORY else FileType.FILE,
        size = size,
        modifiedAt = null,
        allocatedSize = allocation,
    )

    private fun seed() {
        estimator.onEntry(entry("", 100))
        aggregator.onEntry(entry("Android", 100))
        aggregator.onEntry(entry("Android/data", 100))
        aggregator.onEntry(entry("visible", 1000, 1000))
    }

    private fun error(name: String) = aggregator.onError(entry(name, null), "denied")
    private fun result(total: Long = 5000) = estimator.apply(
        aggregator.result(time), target, ExternalStorageStatsProvider.Snapshot(total, time),
    )

    @Test
    fun `partial data is counted once and logical bytes stay separate from allocated bytes`() {
        seed()
        aggregator.onEntry(entry("Android/data/sparse", 200, 2000))
        aggregator.onEntry(entry("Android/obb", 100))
        aggregator.onEntry(entry("Android/obb/large", 10000, 10000))
        error("Android/data/locked")
        error("Android/obb/locked")

        val scan = result()
        scan.estimate!!.outsideAllocatedBytes shouldBe 1200
        scan.estimate!!.measuredDataAllocatedBytes shouldBe 300
        scan.estimate!!.missingAllocatedBytes shouldBe 3500
        scan.sizes.getValue(root.child("Android", "data").path).apply {
            bytes shouldBe 5500
            measuredBytes shouldBe 2000
            isComplete shouldBe false
            isEstimated shouldBe true
            hasUnestimatedContent shouldBe false
        }
        scan.sizes.getValue(root.path).apply {
            bytes shouldBe 16500
            hasUnestimatedContent shouldBe true
        }
        scan.sizes.getValue(root.child("Android", "data", "locked").path).isEstimated shouldBe false
        scan.errorCount shouldBe 2
        scan.problems.size shouldBe 2
    }

    @Test
    fun `fully measured data is never estimated`() {
        seed()
        result().estimate shouldBe null
    }

    @Test
    fun `a zero remainder is an estimate and never proof of completeness`() {
        seed()
        error("Android/data")
        result(1300).sizes.getValue(root.child("Android", "data").path).apply {
            estimatedMissingBytes shouldBe 0
            isComplete shouldBe false
            hasUnestimatedContent shouldBe false
        }
    }

    @Test
    fun `unreadable OBB does not prevent data estimation but keeps ancestors partial`() {
        seed()
        error("Android/data")
        error("Android/obb")
        result().sizes.getValue(root.path).hasUnestimatedContent shouldBe true
    }

    @Test
    fun `without other gaps ancestors have estimated sizes without remaining unknown content`() {
        seed()
        error("Android/data")
        result().sizes.getValue(root.path).hasUnestimatedContent shouldBe false
    }

    @Test
    fun `an outside failure beyond the problem display cap still prevents estimation`() {
        seed()
        repeat(501) { error("Android/data/locked$it") }
        error("Download")
        val scan = result()
        scan.problems.size shouldBe 500
        scan.estimate shouldBe null
        scan.estimateFailure shouldBe AndroidDataEstimate.Failure.INCOMPLETE_COVERAGE
    }

    @Test
    fun `a failure at Android cannot be attributed to data alone`() {
        seed()
        error("Android/data")
        error("Android")
        result().estimate shouldBe null
    }

    @Test
    fun `missing allocation outside data prevents subtraction`() {
        seed()
        aggregator.onEntry(entry("unknown-allocation", null, 20))
        error("Android/data")
        result().estimateFailure shouldBe AndroidDataEstimate.Failure.INCOMPLETE_COVERAGE
    }

    @Test
    fun `missing allocation in measured data prevents double counting`() {
        seed()
        aggregator.onEntry(entry("Android/data/known", null, 20))
        error("Android/data")
        result().estimate shouldBe null
    }

    @Test
    fun `a missing root allocation prevents estimation`() {
        aggregator.onEntry(entry("Android", 100))
        error("Android/data")
        result().estimate shouldBe null
    }

    @Test
    fun `totals below measured allocation are rejected instead of clamped`() {
        seed()
        error("Android/data")
        for (total in listOf(-1L, 0L, 100L, 1200L)) {
            result(total).estimateFailure shouldBe AndroidDataEstimate.Failure.INCONSISTENT_TOTAL
        }
    }

    @Test
    fun `adding estimated bytes cannot overflow the displayed apparent total`() {
        seed()
        aggregator.onEntry(entry("Android/data/sparse", 0, Long.MAX_VALUE - 1000))
        error("Android/data")
        result().estimateFailure shouldBe AndroidDataEstimate.Failure.INCONSISTENT_TOTAL
    }

    @Test
    fun `an overflowing allocation total is rejected`() {
        seed()
        aggregator.onEntry(entry("huge", Long.MAX_VALUE, 0))
        error("Android/data")
        result().estimateFailure shouldBe AndroidDataEstimate.Failure.INCOMPLETE_COVERAGE
    }

    @Test
    fun `a name prefix collision is an outside error`() {
        seed()
        error("Android/database")
        error("Android/data")
        result().estimate shouldBe null
    }
}
