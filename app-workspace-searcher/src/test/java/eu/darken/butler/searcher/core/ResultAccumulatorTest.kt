package eu.darken.butler.searcher.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.engine.backend.SearchBackend
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ResultAccumulatorTest : BaseTest() {

    private fun item(path: String, size: Long? = 1L): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = FileType.FILE,
            size = size,
            modifiedAt = null,
        ),
        matchedQuery = "q",
    )

    private fun fs(path: String, size: Long? = 1L) =
        SearchBackend.BackendResult(item(path, size), SearchBackend.BackendResult.RANK_FILESYSTEM)

    private fun index(path: String, size: Long? = 1L) =
        SearchBackend.BackendResult(item(path, size), SearchBackend.BackendResult.RANK_INDEX)

    @Test
    fun `unique paths are added in order`() {
        val accumulator = ResultAccumulator()
        accumulator.add(fs("/storage/emulated/0/a.txt")) shouldBe ResultAccumulator.Outcome.Added
        accumulator.add(fs("/storage/emulated/0/b.txt")) shouldBe ResultAccumulator.Outcome.Added

        accumulator.uniqueCount shouldBe 2
        accumulator.snapshot().map { it.path.path } shouldContainExactly
            listOf("/storage/emulated/0/a.txt", "/storage/emulated/0/b.txt")
    }

    @Test
    fun `filesystem source replaces an index duplicate in place`() {
        val accumulator = ResultAccumulator()
        accumulator.add(index("/storage/emulated/0/a.jpg", size = 100L))
        accumulator.add(fs("/storage/emulated/0/b.jpg"))

        val fresh = fs("/storage/emulated/0/a.jpg", size = 200L)
        accumulator.add(fresh) shouldBe ResultAccumulator.Outcome.Replaced

        accumulator.uniqueCount shouldBe 2
        val snapshot = accumulator.snapshot()
        // Position preserved, metadata from the filesystem source
        snapshot[0].path.path shouldBe "/storage/emulated/0/a.jpg"
        snapshot[0].size shouldBe 200L
    }

    @Test
    fun `index duplicate of a filesystem item is ignored`() {
        val accumulator = ResultAccumulator()
        accumulator.add(fs("/storage/emulated/0/a.jpg", size = 200L))

        accumulator.add(index("/storage/emulated/0/a.jpg", size = 100L)) shouldBe
            ResultAccumulator.Outcome.Ignored

        accumulator.uniqueCount shouldBe 1
        accumulator.snapshot().single().size shouldBe 200L
    }

    @Test
    fun `same-rank duplicate is ignored`() {
        val accumulator = ResultAccumulator()
        accumulator.add(fs("/storage/emulated/0/a.jpg", size = 1L))
        accumulator.add(fs("/storage/emulated/0/a.jpg", size = 2L)) shouldBe
            ResultAccumulator.Outcome.Ignored

        accumulator.snapshot().single().size shouldBe 1L
    }

    @Test
    fun `alias spellings deduplicate against each other`() {
        val accumulator = ResultAccumulator()
        accumulator.add(index("/storage/emulated/0/DCIM/a.jpg", size = 100L))

        // Same file found via an /sdcard-target walk
        accumulator.add(fs("/sdcard/DCIM/a.jpg", size = 200L)) shouldBe
            ResultAccumulator.Outcome.Replaced

        accumulator.uniqueCount shouldBe 1
        accumulator.snapshot().single().size shouldBe 200L
    }

    @Test
    fun `removeLast drops the sentinel and frees its key`() {
        val accumulator = ResultAccumulator()
        accumulator.add(fs("/storage/emulated/0/a.txt"))
        accumulator.add(fs("/storage/emulated/0/b.txt"))

        accumulator.removeLast().path.path shouldBe "/storage/emulated/0/b.txt"

        accumulator.uniqueCount shouldBe 1
        accumulator.add(fs("/storage/emulated/0/b.txt")) shouldBe ResultAccumulator.Outcome.Added
    }

    @Test
    fun `snapshot honors the limit and always returns a new instance`() {
        val accumulator = ResultAccumulator()
        accumulator.add(fs("/storage/emulated/0/a.txt"))
        accumulator.add(fs("/storage/emulated/0/b.txt"))
        accumulator.add(fs("/storage/emulated/0/c.txt"))

        accumulator.snapshot(limit = 2).map { it.path.path } shouldContainExactly
            listOf("/storage/emulated/0/a.txt", "/storage/emulated/0/b.txt")
        accumulator.snapshot() shouldNotBeSameInstanceAs accumulator.snapshot()
    }

    @Test
    fun `replacement after snapshot produces a fresh snapshot instance`() {
        val accumulator = ResultAccumulator()
        accumulator.add(index("/storage/emulated/0/a.jpg", size = 1L))
        val before = accumulator.snapshot()

        accumulator.add(fs("/storage/emulated/0/a.jpg", size = 2L))
        val after = accumulator.snapshot()

        before shouldNotBeSameInstanceAs after
        before.single().size shouldBe 1L
        after.single().size shouldBe 2L
    }
}
