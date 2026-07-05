package eu.darken.butler.editor.core.engine.text

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

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
}
