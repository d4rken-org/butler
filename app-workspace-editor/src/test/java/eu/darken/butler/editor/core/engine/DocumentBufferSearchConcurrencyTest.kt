package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.engine.text.WindowedSearch
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

/**
 * Search must not hold the buffer lock across the whole scan: edits proceed while a search is
 * in flight, and the search aborts with [SearchInvalidatedException] instead of returning
 * positionally stale matches.
 *
 * The tests shrink the search windows and gate the read wrapper OUTSIDE the buffer lock via
 * the internal windowedSearchFactory seam, so the interleaving is deterministic.
 */
class DocumentBufferSearchConcurrencyTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())

    private suspend fun createBuffer(content: String): DocumentBuffer {
        val dataSource = InMemoryDataSource(workspaceId, content)
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 64,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return buffer
    }

    @Test
    fun `an edit during a search proceeds and invalidates the scan`(): Unit = runBlocking {
        val buffer = createBuffer("needle ".repeat(100))
        val firstWindowRead = CompletableDeferred<Unit>()
        val editLanded = CompletableDeferred<Unit>()
        var reads = 0

        buffer.windowedSearchFactory = { lockedRead ->
            WindowedSearch(baseWindowSize = 64, minOverlap = 8) { start, end ->
                // Gate OUTSIDE the buffer lock: the edit below must be able to run while the
                // search is paused here
                if (++reads == 2) {
                    firstWindowRead.complete(Unit)
                    withTimeout(10_000) { editLanded.await() }
                }
                lockedRead(start, end)
            }
        }

        val search = async(Dispatchers.Default) {
            buffer.search("needle", SearchOptions(caseSensitive = true))
        }

        withTimeout(10_000) { firstWindowRead.await() }
        // The search is mid-scan; this edit must NOT block on it
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()
        editLanded.complete(Unit)

        val result = search.await()
        result.isFailure.shouldBeTrue()
        result.exceptionOrNull().shouldBeInstanceOf<SearchInvalidatedException>()
    }

    @Test
    fun `an undisturbed search still returns exact matches`() = runTest {
        val buffer = createBuffer("needle haystack ".repeat(50))
        buffer.windowedSearchFactory = { lockedRead ->
            WindowedSearch(baseWindowSize = 64, minOverlap = 8, readText = lockedRead)
        }

        val results = buffer.search("needle", SearchOptions(caseSensitive = true)).getOrThrow()

        results.size shouldBe 50
        results.first().position.offset shouldBe 0L
    }

    @Test
    fun `cancelling a search stops the scan at the next window`(): Unit = runBlocking {
        val buffer = createBuffer("data ".repeat(200))
        val midScan = CompletableDeferred<Unit>()
        val holdScan = CompletableDeferred<Unit>()
        var reads = 0

        buffer.windowedSearchFactory = { lockedRead ->
            WindowedSearch(baseWindowSize = 64, minOverlap = 8) { start, end ->
                if (++reads == 2) {
                    midScan.complete(Unit)
                    withTimeout(10_000) { holdScan.await() }
                }
                lockedRead(start, end)
            }
        }

        val search = async(Dispatchers.Default) {
            buffer.search("missing-needle", SearchOptions(caseSensitive = true))
        }

        withTimeout(10_000) { midScan.await() }
        search.cancel()
        holdScan.complete(Unit)

        shouldThrow<CancellationException> { search.await() }
        // The buffer stays fully usable after a cancelled scan
        buffer.insertText(TextPosition(0, 0, 0), "Y").getOrThrow()
        buffer.getText(0, 1).getOrThrow() shouldBe "Y"
    }
}
