package eu.darken.butler.common.coroutine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.IOException
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real time, not [kotlinx.coroutines.test.runTest]: the point of the helper is that it survives a
 * thread blocked in a synchronous call, which virtual time would skip right past.
 */
class CoroutineExtensionsTest : BaseTest() {

    @Test fun `the block's result is returned when it finishes in time`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)

        scope.runDetachedWithTimeout(Dispatchers.IO, 10 * 1000L) { 42 } shouldBe 42

        scope.cancel()
    }

    @Test fun `a blocked thread does not pin the caller`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val release = CountDownLatch(1)

        val start = System.currentTimeMillis()
        val result = scope.runDetachedWithTimeout(Dispatchers.IO, 100L) {
            // Uninterruptible from the caller's side, exactly like a binder transaction.
            release.await(30, TimeUnit.SECONDS)
            true
        }
        val elapsed = System.currentTimeMillis() - start

        result shouldBe null
        (elapsed < 5 * 1000L) shouldBe true

        release.countDown()
        scope.cancel()
    }

    @Test fun `exceptions from the block reach the caller`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)

        shouldThrow<IOException> {
            scope.runDetachedWithTimeout(Dispatchers.IO, 10 * 1000L) { throw IOException("nope") }
        }

        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `giving up drops work that never started`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        // One lane, occupied: the detached block can only be queued, never entered.
        val singleLane = Dispatchers.IO.limitedParallelism(1)
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockRan = AtomicBoolean(false)

        val hog = scope.launch(singleLane) {
            occupied.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        occupied.await(30, TimeUnit.SECONDS)

        scope.runDetachedWithTimeout(singleLane, 100L) { blockRan.set(true) } shouldBe null

        release.countDown()
        hog.join()
        // The finally-cancel ran before the lane freed up, so the queued block was dropped.
        blockRan.get() shouldBe false

        scope.cancel()
    }
}
