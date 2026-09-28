package eu.darken.butler.common.ipc

import android.os.IBinder
import android.os.IInterface
import eu.darken.butler.common.adb.service.AdbHostOptions
import eu.darken.butler.common.adb.service.AdbServiceClient
import eu.darken.butler.common.adb.service.internal.AdbConnection
import eu.darken.butler.common.adb.service.internal.AdbHostLauncher
import eu.darken.butler.common.adb.service.internal.AdbUserService
import eu.darken.butler.common.adb.service.internal.AdbUserServiceFactory
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.sharedresource.SharedResource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The identity gate on top of the REAL [AdbHostLauncher], because that is where the gate's recovery
 * and the launcher's teardown have to line up: the launcher's stop is detached under a timeout, so its
 * producer coroutine can finish with the stop still in flight, and rebinding then risks that late stop
 * taking out the replacement instead. The SDK is replaced via the launcher's seam
 * (AdbHostLauncherSeam.kt).
 */
class IpcHostIdentityGateAdbTest : BaseTest() {

    private val ours = IpcContract.HostIdentity(
        versionCode = 12345,
        versionName = "1.2.3",
        lastUpdateTime = 1755800000000,
        packageCodePath = "/data/app/~~aB1/eu.darken.butler-Xy2/base.apk",
    )

    /** A host left over from the previous installation: same version, earlier install timestamp. */
    private val stale = ours.copy(lastUpdateTime = ours.lastUpdateTime - 5000)

    private val binds = AtomicInteger()
    private val checks = AtomicInteger()
    private val created = LinkedBlockingQueue<FakeService>()
    private val binder = mockk<IBinder>()

    /** One user service per host generation; its binder flow completes on [end], like the SDK's. */
    private inner class FakeService(private val onStop: suspend FakeService.() -> Unit) : AdbUserService {
        private val connected = Channel<IBinder>(Channel.UNLIMITED)

        fun connect() {
            connected.trySend(binder)
        }

        fun end() {
            connected.close()
        }

        override fun binders(): Flow<IBinder> = flow {
            binds.incrementAndGet()
            for (binder in connected) emit(binder)
        }

        override suspend fun stop() = onStop(this)
    }

    private inner class FakeFactory(private val onStop: suspend FakeService.() -> Unit) : AdbUserServiceFactory {

        override fun <Host : AdbConnection> create(
            hostClass: KClass<Host>,
            options: AdbHostOptions,
        ): AdbUserService = FakeService(onStop).also { created.put(it) }

        @Suppress("UNCHECKED_CAST")
        override fun <Service : IInterface, Host : AdbConnection> handshake(
            binder: IBinder,
            serviceClass: KClass<Service>,
            options: AdbHostOptions,
        ): Pair<Service, Host> = (mockk<AdbConnection>() as Service) to (mockk<AdbConnection>() as Host)
    }

    /**
     * Stands in for the server reporting each service connected, for however many generations get
     * bound, which is the thing under test, so it can't be a fixed number of hand-fired connects.
     */
    private fun startConnectPump(): Thread = Thread {
        try {
            while (!Thread.currentThread().isInterrupted) {
                created.poll(100, TimeUnit.MILLISECONDS)?.connect()
            }
        } catch (_: InterruptedException) {
            // Shutting down
        }
    }.apply {
        isDaemon = true
        start()
    }

    /**
     * Bounded await, [withTimeoutOrNull] plus an explicit failure. Never `withTimeout` here: its
     * `TimeoutCancellationException` unwinds a test body as a cancellation rather than a failure, so a
     * regression that re-introduces a hang would be reported as a pass.
     */
    private suspend fun <T> awaitOrFail(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(5 * 1000L) { block() } ?: throw AssertionError("Timed out waiting for $what")

    /** The bind runs detached from the flow, so its count is only stable once it has caught up. */
    private suspend fun awaitBinds(expected: Int) = awaitOrFail("$expected bind(s)") {
        while (binds.get() < expected) delay(10)
    }

    /** The launcher under the gate, answering [replies] in order, one per host generation. */
    private fun AdbHostLauncher.gated(vararg replies: String, unbindTimeoutMs: Long) = createConnection(
        serviceClass = AdbConnection::class,
        hostClass = AdbConnection::class,
        // Explicit values: AdbHostOptions()'s isDebug default triggers BuildConfigWrap's static init,
        // which isn't available on a plain JVM.
        options = AdbHostOptions(isDebug = false, isTrace = false, recorderPath = null),
        unbindTimeoutMs = unbindTimeoutMs,
    )
        .map { IpcHostAttempt(it.service, it.disconnectConfirmed) }
        .gateOnHostIdentity(
            tag = "test",
            expected = { ours },
            checkBase = { replies[minOf(checks.getAndIncrement(), replies.lastIndex)] },
            onAccepted = { _, identity -> "connection#${identity.lastUpdateTime}" },
        )

    private fun launcher(onStop: suspend FakeService.() -> Unit, scope: CoroutineScope) = AdbHostLauncher(
        serviceFactory = FakeFactory(onStop),
        appScope = scope,
        // Real dispatchers: the wedge below blocks an actual thread, virtual time can't model that.
        dispatcherProvider = TestDispatcherProvider(Dispatchers.IO),
    )

    @Test fun `a mismatch does not rebind while the stale stop is still in flight`() = runTest(
        timeout = 10.seconds,
    ) {
        val stopEntered = CompletableDeferred<Unit>()
        val stopWedge = CountDownLatch(1)
        val realScope = CoroutineScope(SupervisorJob())
        val hostLauncher = launcher(
            onStop = {
                stopEntered.complete(Unit)
                stopWedge.await() // blocks the thread, unaffected by coroutine cancellation
            },
            scope = realScope,
        )
        val pump = startConnectPump()

        try {
            val result = realScope.async(Dispatchers.Default) {
                runCatching { hostLauncher.gated(stale.encode(), ours.encode(), unbindTimeoutMs = 250L).first() }
            }

            withContext(Dispatchers.Default) {
                awaitOrFail("the stop to be entered") { stopEntered.await() }

                // Teardown gives up on the wedged stop, and an unconfirmed teardown is not a base to
                // rebind on: the mismatch reaches the caller instead.
                val error = awaitOrFail("the collector to be released") { result.await() }.exceptionOrNull()
                error.shouldBeInstanceOf<IpcContractMismatchException>()
            }

            // The whole point: no second generation for the in-flight stop to take out.
            binds.get() shouldBe 1
        } finally {
            stopWedge.countDown()
            pump.interrupt()
            realScope.cancel()
        }
    }

    @Test fun `a mismatch rebinds once the teardown is confirmed`() = runTest(timeout = 10.seconds) {
        val realScope = CoroutineScope(SupervisorJob())
        // The stop ends the service, which is what confirms the teardown.
        val hostLauncher = launcher(onStop = { end() }, scope = realScope)
        val pump = startConnectPump()

        try {
            val result = realScope.async(Dispatchers.Default) {
                runCatching { hostLauncher.gated(stale.encode(), ours.encode(), unbindTimeoutMs = 250L).first() }
            }

            withContext(Dispatchers.Default) {
                val connection = awaitOrFail("the replacement connection") { result.await() }.getOrThrow()
                connection shouldBe "connection#${ours.lastUpdateTime}"

                awaitBinds(2)
            }
        } finally {
            pump.interrupt()
            realScope.cancel()
        }
    }

    /**
     * The gate refusing to rebind only settles the gate's own retry. [SharedResource] has a second
     * one: a caller that latched onto a generation which died before producing a value gets that
     * generation detached and a FRESH source collection started — the rebind the gate just declined,
     * with the stale stop still in flight. Wired with the predicate AdbServiceClient passes, so
     * this covers the layer the gate-only tests above cannot see.
     */
    @Test fun `a mismatch is not rebound by the shared resource retry either`() = runTest(timeout = 10.seconds) {
        val stopEntered = CompletableDeferred<Unit>()
        val stopWedge = CountDownLatch(1)
        val realScope = CoroutineScope(SupervisorJob())
        val hostLauncher = launcher(
            onStop = {
                stopEntered.complete(Unit)
                stopWedge.await() // blocks the thread, unaffected by coroutine cancellation
            },
            scope = realScope,
        )

        // Only a REUSING get() logs this, and it is trace-gated. Without waiting for it, the second
        // caller could arrive after generation 1 already died and pass vacuously on a fresh one.
        val reuserLatched = CompletableDeferred<Unit>()
        val capture = object : Logging.Logger {
            override fun log(
                priority: Logging.Priority,
                tag: String,
                message: String,
                metaData: Map<String, Any>?,
            ) {
                if (tag == "$SR_TAG:SR" && message.contains("Source job already exists")) {
                    reuserLatched.complete(Unit)
                }
            }
        }
        Bugs.isTrace = true
        Logging.install(capture)

        val sharedResource = SharedResource(
            tag = SR_TAG,
            parentScope = realScope + Dispatchers.IO,
            // A single reply, so any rebind lands on the same stale host again.
            source = hostLauncher.gated(stale.encode(), unbindTimeoutMs = 250L),
            stopTimeout = Duration.ZERO,
            // The very value AdbServiceClient installs, not a copy of it.
            isRetryableStartupFailure = AdbServiceClient.RETRYABLE_STARTUP_FAILURE,
        )
        var pump: Thread? = null

        try {
            withContext(Dispatchers.Default) {
                val creator = realScope.async(Dispatchers.IO) { runCatching { sharedResource.get() } }
                // Generation 1 is installed and its source is running, but its service has not been
                // reported connected yet, so it cannot have produced a value.
                awaitBinds(1)

                val reuser = realScope.async(Dispatchers.IO) { runCatching { sharedResource.get() } }
                awaitOrFail("the second caller to latch onto generation 1") { reuserLatched.await() }

                // Only now let the host connect, mismatch, and wedge its stop.
                pump = startConnectPump()
                awaitOrFail("the stop to be entered") { stopEntered.await() }

                awaitOrFail("the starting caller to be released") { creator.await() }
                    .exceptionOrNull().shouldBeInstanceOf<IpcContractMismatchException>()
                // The waiter must inherit the mismatch instead of retrying onto a fresh generation.
                awaitOrFail("the reusing caller to be released") { reuser.await() }
                    .exceptionOrNull().shouldBeInstanceOf<IpcContractMismatchException>()
            }

            // The whole point: no second generation for the in-flight stop to take out.
            binds.get() shouldBe 1
        } finally {
            stopWedge.countDown()
            pump?.interrupt()
            realScope.cancel()
            Logging.remove(capture)
            Bugs.isTrace = false
        }
    }
}

private const val SR_TAG = "gate-shared-resource"
