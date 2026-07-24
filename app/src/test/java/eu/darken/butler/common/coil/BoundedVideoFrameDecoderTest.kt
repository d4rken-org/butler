package eu.darken.butler.common.coil

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class BoundedVideoFrameDecoderTest : BaseTest() {

    private val fetchResult: SourceFetchResult = mockk()
    private val options: Options = mockk()
    private val imageLoader: ImageLoader = mockk()

    private class GatedDecoder(
        private val gate: CompletableDeferred<Unit>,
        private val result: DecodeResult? = mockk(),
    ) : Decoder {
        val invoked = AtomicInteger(0)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        override suspend fun decode(): DecodeResult? {
            invoked.incrementAndGet()
            val now = active.incrementAndGet()
            maxActive.updateAndGet { max(it, now) }
            try {
                gate.await()
                return result
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun factoryFor(decoder: Decoder?, parallelism: Int = 2) = BoundedVideoFrameDecoder.Factory(
        delegate = { _, _, _ -> decoder },
        parallelism = parallelism,
        baseDispatcher = Dispatchers.Default,
    )

    @Test
    fun `returns null when the delegate factory declines (non-video)`() {
        factoryFor(decoder = null).create(fetchResult, options, imageLoader) shouldBe null
    }

    @Test
    fun `delegates decode and returns the result`() = runBlocking<Unit> {
        val expected: DecodeResult = mockk()
        val inner = GatedDecoder(CompletableDeferred<Unit>().also { it.complete(Unit) }, result = expected)
        val decoder = factoryFor(inner).create(fetchResult, options, imageLoader).shouldNotBeNull()

        decoder.decode() shouldBe expected
        inner.invoked.get() shouldBe 1
    }

    @Test
    fun `concurrent decodes are bounded to the configured parallelism`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val inner = GatedDecoder(gate)
        val factory = factoryFor(inner, parallelism = 2)

        val jobs = (1..4).map {
            async { factory.create(fetchResult, options, imageLoader).shouldNotBeNull().decode() }
        }

        withTimeout(5000) {
            while (inner.invoked.get() < 2) delay(5)
        }
        // Give the remaining two a chance to (incorrectly) start
        delay(100)
        inner.invoked.get() shouldBe 2
        inner.active.get() shouldBe 2

        gate.complete(Unit)
        jobs.awaitAll()

        inner.invoked.get() shouldBe 4
        inner.maxActive.get() shouldBeLessThanOrEqual 2
    }

    @Test
    fun `a decode cancelled while queued never starts the native work`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val blocker = GatedDecoder(gate)
        val victim = GatedDecoder(CompletableDeferred<Unit>().also { it.complete(Unit) })
        // ONE factory (shared dispatcher, single slot); delegate hands out decoders in order
        val decoders = ArrayDeque<Decoder>(listOf(blocker, victim))
        val factory = BoundedVideoFrameDecoder.Factory(
            delegate = { _, _, _ -> decoders.removeFirst() },
            parallelism = 1,
            baseDispatcher = Dispatchers.Default,
        )

        val blockingJob = launch { factory.create(fetchResult, options, imageLoader)!!.decode() }
        withTimeout(5000) {
            while (blocker.invoked.get() < 1) delay(5)
        }

        // The single slot is occupied -> the victim queues; cancelling it must prevent its decode.
        // UNDISPATCHED runs the victim inline until it suspends on the occupied semaphore, so the
        // cancellation deterministically hits a QUEUED decode (not one that never started).
        val victimJob = launch(start = CoroutineStart.UNDISPATCHED) {
            factory.create(fetchResult, options, imageLoader)!!.decode()
        }
        victimJob.cancel()
        victimJob.join()

        gate.complete(Unit)
        blockingJob.join()

        victim.invoked.get() shouldBe 0
    }

    @Test
    fun `permit is released when the delegate throws`() = runBlocking<Unit> {
        var first = true
        val factory = BoundedVideoFrameDecoder.Factory(
            delegate = { _, _, _ ->
                if (first) {
                    first = false
                    Decoder { throw IllegalStateException("decode boom") }
                } else {
                    GatedDecoder(CompletableDeferred<Unit>().also { it.complete(Unit) })
                }
            },
            parallelism = 1,
            baseDispatcher = Dispatchers.Default,
        )

        runCatching { factory.create(fetchResult, options, imageLoader)!!.decode() }
            .exceptionOrNull().shouldNotBeNull()

        // If the permit leaked, this second decode would hang on the single-permit semaphore
        withTimeout(5000) {
            factory.create(fetchResult, options, imageLoader)!!.decode().shouldNotBeNull()
        }
    }
}
