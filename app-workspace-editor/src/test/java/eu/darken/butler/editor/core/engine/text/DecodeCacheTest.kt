package eu.darken.butler.editor.core.engine.text

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException

class DecodeCacheTest : BaseTest() {

    private fun countingCache(maxBlocks: Int): Pair<DecodeCache, MutableMap<Int, Int>> {
        val loads = mutableMapOf<Int, Int>()
        val cache = DecodeCache(maxBlocks) { block ->
            loads.merge(block, 1, Int::plus)
            "block-$block"
        }
        return cache to loads
    }

    @Test
    fun `loader runs once per cached block`() = runTest {
        val (cache, loads) = countingCache(maxBlocks = 4)
        cache.get(1) shouldBe "block-1"
        cache.get(1) shouldBe "block-1"
        cache.get(2) shouldBe "block-2"
        loads shouldBe mapOf(1 to 1, 2 to 1)
    }

    @Test
    fun `evicts least recently used block`() = runTest {
        val (cache, loads) = countingCache(maxBlocks = 2)
        cache.get(0)
        cache.get(1)
        cache.get(0)
        cache.get(2)
        cache.get(0)
        loads[0] shouldBe 1
        cache.get(1)
        loads[1] shouldBe 2
    }

    @Test
    fun `evicts beyond capacity`() = runTest {
        val (cache, loads) = countingCache(maxBlocks = 2)
        cache.get(0)
        cache.get(1)
        cache.get(2)
        cache.get(0)
        loads[0] shouldBe 2
    }

    @Test
    fun `clear drops all entries`() = runTest {
        val (cache, loads) = countingCache(maxBlocks = 4)
        cache.get(7)
        cache.clear()
        cache.get(7)
        loads[7] shouldBe 2
    }

    @Test
    fun `concurrent access loads once`() = runTest {
        val (cache, loads) = countingCache(maxBlocks = 4)
        coroutineScope {
            val jobs = (1..20).map { async { cache.get(5) } }
            jobs.forEach { it.await() shouldBe "block-5" }
        }
        loads[5] shouldBe 1
    }

    @Test
    fun `concurrent same-key gets share one in-flight load`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val cache = DecodeCache(4) { block ->
            loads++
            gate.await()
            "block-$block"
        }
        val jobs = (1..3).map { async { cache.get(5) } }
        testScheduler.runCurrent()
        loads shouldBe 1
        gate.complete(Unit)
        jobs.forEach { it.await() shouldBe "block-5" }
        loads shouldBe 1
    }

    @Test
    fun `distinct keys load concurrently`() = runTest {
        val gate0 = CompletableDeferred<Unit>()
        val gate1 = CompletableDeferred<Unit>()
        val started = mutableSetOf<Int>()
        val cache = DecodeCache(4) { block ->
            started += block
            when (block) {
                0 -> gate0.await()
                1 -> gate1.await()
            }
            "block-$block"
        }
        val a = async { cache.get(0) }
        val b = async { cache.get(1) }
        testScheduler.runCurrent()
        // Both loads in flight at once - serialized loading would leave block 1 unstarted
        started shouldBe setOf(0, 1)
        gate1.complete(Unit)
        b.await() shouldBe "block-1"
        gate0.complete(Unit)
        a.await() shouldBe "block-0"
    }

    @Test
    fun `loader failure deregisters and allows retry`() = runTest {
        var attempts = 0
        val cache = DecodeCache(4) { block ->
            attempts++
            if (attempts == 1) throw IOException("boom")
            "block-$block"
        }
        shouldThrow<IOException> { cache.get(3) }
        cache.get(3) shouldBe "block-3"
        attempts shouldBe 2
    }

    @Test
    fun `waiter retries after the owning load fails`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val cache = DecodeCache(4) { block ->
            attempts++
            if (attempts == 1) {
                gate.await()
                throw IOException("boom")
            }
            "block-$block"
        }
        val owner = async { runCatching { cache.get(9) } }
        val waiter = async { cache.get(9) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        owner.await().exceptionOrNull().shouldBeInstanceOf<IOException>()
        waiter.await() shouldBe "block-9"
        attempts shouldBe 2
    }

    @Test
    fun `clear during load discards the result from the cache`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val cache = DecodeCache(4) { block ->
            loads++
            if (loads == 1) gate.await()
            "block-$block"
        }
        val inFlightGet = async { cache.get(2) }
        testScheduler.runCurrent()
        cache.clear()
        gate.complete(Unit)
        // The in-flight load finishes and its caller gets the text, but the result is discarded
        inFlightGet.await() shouldBe "block-2"
        cache.get(2) shouldBe "block-2"
        loads shouldBe 2
    }
}
