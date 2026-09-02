package eu.darken.butler.common.coroutine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.IOException
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The [runDetachedWithTimeout] tests run in real time, not [kotlinx.coroutines.test.runTest]: the
 * point of that helper is that it survives a thread blocked in a synchronous call, which virtual
 * time would skip right past.
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

    private class TestCloseable : Closeable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
        }
    }

    @Test fun `a resource opened while the caller is being cancelled is closed`() = runTest {
        // Distinct dispatchers, so the hand-back is a real dispatched resume.
        val callerDispatcher = StandardTestDispatcher(testScheduler)
        val openDispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val resource = TestCloseable()
        var received: TestCloseable? = null

        val job = launch(callerDispatcher) {
            received = openForHandover(openDispatcher) {
                gate.await()
                resource
            }
        }
        runCurrent()

        job.cancel()
        gate.complete(Unit)
        runCurrent()

        resource.closeCount shouldBe 1
        received shouldBe null
        job.isCancelled shouldBe true
    }

    @Test fun `a resource handed back to a cancelled caller is closed`() = runTest {
        // One dispatcher for both, so withContext resumes undispatched and reports no cancellation.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val resource = TestCloseable()
        var received: TestCloseable? = null

        val job = launch(dispatcher) {
            received = openForHandover(dispatcher) {
                gate.await()
                resource
            }
        }
        runCurrent()

        job.cancel()
        gate.complete(Unit)
        runCurrent()

        resource.closeCount shouldBe 1
        received shouldBe null
        job.isCancelled shouldBe true
    }
}
