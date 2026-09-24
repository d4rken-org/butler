package eu.darken.butler.common.adb.service.internal

import android.content.ComponentName
import android.os.IBinder
import android.os.IInterface
import eu.darken.butler.common.adb.AdbConnectTimeoutException
import eu.darken.butler.common.adb.AdbException
import eu.darken.butler.common.adb.service.AdbHostOptions
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.adb.shizuku.AdbPermissionState
import eu.darken.butler.common.adb.shizuku.AdbServerConnection
import eu.darken.porter.sdk.UserServiceArgs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import testhelpers.coroutine.TestDispatcherProvider
import java.util.concurrent.CountDownLatch
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

/**
 * Unit coverage for [AdbHostLauncher.createConnection]'s teardown/orchestration. The SDK's user service
 * is replaced with a fake via the injectable seam (AdbHostLauncherSeam.kt).
 */
class AdbHostLauncherTest {

    private val events = mutableListOf<String>()

    /**
     * Bounded await for the wedge tests, [withTimeoutOrNull] plus an explicit failure.
     *
     * Never `withTimeout` here: its `TimeoutCancellationException` unwinds a test body as a
     * cancellation rather than a failure, so a regression that re-introduces a wedge would be
     * reported as a pass.
     */
    private suspend fun <T> awaitOrFail(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(5 * 1000L) { block() } ?: throw AssertionError("Timed out waiting for $what")

    /**
     * Stands in for one user service on one server connection. Its binder flow emits whatever
     * [connect] hands it and completes on [end], like the SDK's does when the service dies.
     */
    private inner class FakeService(
        val bindError: Throwable? = null,
        val onStop: suspend FakeService.() -> Unit = { end() },
    ) : AdbUserService {
        private val connected = Channel<IBinder>(Channel.UNLIMITED)

        @Volatile var collecting = false
        @Volatile var stoppedWhileCollecting: Boolean? = null
        val ended = CompletableDeferred<Unit>()

        fun connect(binder: IBinder = mockk()) {
            connected.trySend(binder)
        }

        fun end() {
            connected.close()
        }

        override fun binders(): Flow<IBinder> = flow {
            events += "bind"
            bindError?.let { throw it }
            collecting = true
            try {
                for (binder in connected) emit(binder)
                events += "ended"
                ended.complete(Unit)
            } finally {
                collecting = false
            }
        }

        override suspend fun stop() {
            events += "stop"
            stoppedWhileCollecting = collecting
            onStop(this)
        }
    }

    private inner class FakeFactory(
        val service: AdbUserService? = FakeService(),
        val handshakeError: Throwable? = null,
    ) : AdbUserServiceFactory {
        override fun <Host : AdbConnection> create(
            hostClass: KClass<Host>,
            options: AdbHostOptions,
        ): AdbUserService? = service

        @Suppress("UNCHECKED_CAST")
        override fun <Service : IInterface, Host : AdbConnection> handshake(
            binder: IBinder,
            serviceClass: KClass<Service>,
            options: AdbHostOptions,
        ): Pair<Service, Host> {
            events += "handshake"
            handshakeError?.let { throw it }
            return (mockk<AdbConnection>() as Service) to (mockk<AdbConnection>() as Host)
        }
    }

    private fun TestScope.launcher(factory: AdbUserServiceFactory) = AdbHostLauncher(
        serviceFactory = factory,
        appScope = backgroundScope,
        dispatcherProvider = TestDispatcherProvider(),
    )

    // Explicit values: AdbHostOptions()'s default isDebug=BuildConfigWrap.DEBUG triggers
    // BuildConfigWrap's static init, which isn't available on a plain JVM.
    private val options = AdbHostOptions(isDebug = false, isTrace = false, recorderPath = null)

    private fun AdbHostLauncher.connect(
        connectTimeoutMs: Long = AdbHostLauncher.CONNECT_TIMEOUT_MS,
        unbindTimeoutMs: Long = AdbHostLauncher.UNBIND_TIMEOUT_MS,
    ) = createConnection(
        serviceClass = AdbConnection::class,
        hostClass = AdbConnection::class,
        options = options,
        connectTimeoutMs = connectTimeoutMs,
        unbindTimeoutMs = unbindTimeoutMs,
    )

    @Test fun `no server connection fails fast without binding`() = runTest {
        val l = launcher(FakeFactory(service = null))

        val error = shouldThrow<AdbException> { l.connect().collect { } }

        error.message!! shouldContain "No ADB access server"
        events.shouldBeEmpty()
    }

    @Test fun `cancel stops the service while its collection is live, then waits for it to end`() = runTest {
        val service = FakeService()
        val l = launcher(FakeFactory(service))

        val job = launch { l.connect().collect { } }
        // runCurrent, not advanceUntilIdle: the fakes never connect, so advancing virtual time would
        // trip the connect-watchdog instead of testing the cancellation teardown.
        runCurrent()
        job.cancelAndJoin()

        events shouldContainInOrder listOf("bind", "stop", "ended")
        service.stoppedWhileCollecting shouldBe true
    }

    @Test fun `a failing stop is best-effort and teardown still waits for the end`() = runTest {
        val service = FakeService(onStop = {
            end()
            throw IllegalStateException("stop boom")
        })
        val l = launcher(FakeFactory(service))

        val job = launch { l.connect().collect { } }
        runCurrent()
        job.cancelAndJoin() // must not throw

        events shouldContainInOrder listOf("bind", "stop", "ended")
    }

    @Test fun `a service that never ends is bounded and its collection cancelled`() = runTest {
        val service = FakeService(onStop = { })
        val l = launcher(FakeFactory(service))

        val job = launch { l.connect().collect { } }
        runCurrent()
        job.cancelAndJoin() // runTest advances virtual time through the bounded wait

        events shouldContainInOrder listOf("bind", "stop")
        events shouldNotContain "ended"
        // Dropping the collection is what leaves the binding to the SDK to release.
        service.collecting shouldBe false
    }

    @Test fun `unexpected disconnect closes the connection and still tears down`() = runTest {
        val service = FakeService()
        val l = launcher(FakeFactory(service))

        val caught = CompletableDeferred<Throwable>()
        val job = launch {
            try {
                l.connect().collect { }
            } catch (e: Throwable) {
                caught.complete(e)
            }
        }
        runCurrent()

        service.connect()
        runCurrent()
        service.end() // the service died on a server that still runs
        advanceUntilIdle()

        val error = caught.await()
        error.shouldBeInstanceOf<AdbException>() // flow closed instead of leaking a dead connection
        error.message!! shouldContain "disconnected"
        events shouldContainInOrder listOf("bind", "handshake", "ended", "stop") // finally still stopped
        job.cancelAndJoin()
    }

    @Test fun `connection replacement mid-session closes the generation`() = runTest {
        val first = FakeServerConnection()
        val second = FakeServerConnection()
        var current: AdbServerConnection? = first
        val l = launcher(defaultFactoryWithFakeHandshake(currentServer = { current }))

        val caught = CompletableDeferred<Throwable>()
        val generation1 = launch {
            try {
                l.connect().collect { }
            } catch (e: Throwable) {
                caught.complete(e)
            }
        }
        runCurrent()
        first.connect()
        runCurrent()

        // The SDK completes every user service flow of a connection that was replaced.
        current = second
        first.replaced()
        advanceUntilIdle()

        caught.await().shouldBeInstanceOf<AdbException>().message!! shouldContain "disconnected"
        generation1.cancelAndJoin()

        // The next generation binds on the replacement, never on the connection that is gone.
        val generation2 = launch { l.connect().collect { } }
        runCurrent()
        first.bindArgs shouldHaveSize 1
        second.bindArgs shouldHaveSize 1
        generation2.cancelAndJoin()
    }

    @Test fun `bind and stop receive equal UserServiceArgs`() = runTest {
        val server = FakeServerConnection()
        val component = mockk<ComponentName>()
        val factory = defaultFactoryWithFakeHandshake(
            currentServer = { server },
            serviceArgs = { _, options -> adbHostServiceArgs(component, options, versionCode = 4711) },
        )
        val l = launcher(factory)

        val job = launch { l.connect().collect { } }
        runCurrent()
        job.cancelAndJoin()

        val bound = server.bindArgs.single()
        server.stopArgs.single() shouldBe bound
        bound.componentName shouldBe component
        bound.tag shouldBe ADB_HOST_SERVICE_TAG
        bound.version shouldBe 4711
        bound.debuggable shouldBe options.isDebug
        bound.daemon shouldBe false
    }

    @Test fun `a failing bind does not attempt a stop`() = runTest {
        val l = launcher(FakeFactory(FakeService(bindError = IllegalStateException("bind boom"))))

        shouldThrow<IllegalStateException> { l.connect().collect { } }

        events shouldBe listOf("bind") // nothing of ours to stop
        events shouldNotContain "stop"
    }

    @Test fun `a bind that never connects fails with AdbConnectTimeoutException after the timeout`() = runTest {
        // Upstream Shizuku defect: the bind returns fine but the service never reports connected.
        val l = launcher(FakeFactory(FakeService()))

        val caught = CompletableDeferred<Throwable>()
        val job = launch {
            try {
                l.connect().collect { }
            } catch (e: Throwable) {
                caught.complete(e)
            }
        }
        advanceUntilIdle() // past the connect deadline

        val error = caught.await()
        error.shouldBeInstanceOf<AdbConnectTimeoutException>()
        error.message!! shouldContain "did not connect"
        events shouldContainInOrder listOf("bind", "stop", "ended") // teardown still ran
        job.cancelAndJoin()
    }

    @Test fun `a failing handshake closes the flow bounded`() = runTest {
        val service = FakeService()
        val l = launcher(FakeFactory(service, handshakeError = IllegalStateException("handshake boom")))

        val caught = CompletableDeferred<Throwable>()
        val job = launch {
            try {
                l.connect().collect { }
            } catch (e: Throwable) {
                caught.complete(e)
            }
        }
        runCurrent()

        service.connect()
        advanceUntilIdle()

        val error = caught.await()
        error.shouldBeInstanceOf<AdbException>()
        error.message!! shouldContain "handshake failed"
        events shouldContainInOrder listOf("bind", "handshake", "stop")
        job.cancelAndJoin()
    }

    @Test fun `watchdog does not fire after a successful connect`() = runTest {
        val service = FakeService()
        val l = launcher(FakeFactory(service))

        val emitted = mutableListOf<AdbHostLauncher.ConnectionWrapper<AdbConnection, AdbConnection>>()
        val caught = CompletableDeferred<Throwable>()
        val job = launch {
            try {
                l.connect().collect { emitted += it }
            } catch (e: Throwable) {
                caught.complete(e)
            }
        }
        runCurrent()

        service.connect()
        runCurrent()
        emitted shouldHaveSize 1

        advanceTimeBy(60 * 1000L) // way past the connect deadline
        advanceUntilIdle()

        caught.isCompleted shouldBe false // still connected, nothing was torn down
        emitted shouldHaveSize 1
        events shouldNotContain "stop"
        job.cancelAndJoin()
    }

    @Test fun `a bind wedged in its binder transaction still releases collectors after the timeout`() = runTest(
        timeout = 10.seconds,
    ) {
        // Upstream Shizuku defect, second variant: the bind itself never returns (wedged synchronous
        // binder transaction). The blocked thread can't be interrupted, but the watchdog's close() must
        // still release everyone waiting on this flow.
        val bindEntered = CompletableDeferred<Unit>()
        val bindWedge = CountDownLatch(1)
        val stopped = CompletableDeferred<Unit>()
        val service = object : AdbUserService {
            override fun binders(): Flow<IBinder> = flow {
                bindEntered.complete(Unit)
                bindWedge.await() // blocks the calling thread, unaffected by coroutine cancellation
            }

            override suspend fun stop() {
                stopped.complete(Unit)
            }
        }
        // Real scope + real IO dispatcher: the wedge blocks an actual thread, virtual time and
        // Unconfined execution can't model it (Unconfined would block the producer's own thread).
        val realScope = CoroutineScope(SupervisorJob())
        val l = AdbHostLauncher(
            serviceFactory = FakeFactory(service),
            appScope = realScope,
            dispatcherProvider = TestDispatcherProvider(Dispatchers.IO),
        )

        try {
            // Collection runs in its own scope: on a regression the collector blocks forever, and
            // it must do so in a coroutine the test only awaits WITH a timeout - a blocked child
            // of the test coroutine itself would defeat runTest's timeout (non-cooperative
            // cancellation) and hang the JVM.
            val collectResult = realScope.async(Dispatchers.Default) {
                runCatching { l.connect(connectTimeoutMs = 250L).collect { } }
            }

            withContext(Dispatchers.Default) {
                // Only measure once the wedge is real: the bind has been entered and is blocked.
                awaitOrFail("the bind to be entered") { bindEntered.await() }

                val error = awaitOrFail("the collector to be released") { collectResult.await() }.exceptionOrNull()
                error.shouldBeInstanceOf<AdbConnectTimeoutException>()
                error.message!! shouldContain "did not connect"

                // The stop still went out: whatever the wedged bind eventually starts is ours to end.
                stopped.isCompleted shouldBe true
            }
        } finally {
            bindWedge.countDown()
            realScope.cancel()
        }
    }

    @Test fun `a stop wedged in its binder transaction still releases collectors`() = runTest(
        timeout = 10.seconds,
    ) {
        // The watchdog close()s the channel, but collection of a callbackFlow awaits its producer, so
        // an unbounded stop in the teardown finally pins every collector anyway.
        val stopEntered = CompletableDeferred<Unit>()
        val stopWedge = CountDownLatch(1)
        val service = object : AdbUserService {
            override fun binders(): Flow<IBinder> = flow { awaitCancellation() }

            override suspend fun stop() {
                stopEntered.complete(Unit)
                stopWedge.await() // blocks the thread, unaffected by coroutine cancellation
            }
        }
        val realScope = CoroutineScope(SupervisorJob())
        val l = AdbHostLauncher(
            serviceFactory = FakeFactory(service),
            appScope = realScope,
            dispatcherProvider = TestDispatcherProvider(Dispatchers.IO),
        )

        try {
            val collectResult = realScope.async(Dispatchers.Default) {
                // Never connects, so the watchdog fires and teardown runs into the wedged stop.
                runCatching { l.connect(connectTimeoutMs = 250L, unbindTimeoutMs = 250L).collect { } }
            }

            withContext(Dispatchers.Default) {
                awaitOrFail("the stop to be entered") { stopEntered.await() }

                val error = awaitOrFail("the collector to be released") { collectResult.await() }.exceptionOrNull()
                error.shouldBeInstanceOf<AdbConnectTimeoutException>()
                error.message!! shouldContain "did not connect"
            }
        } finally {
            stopWedge.countDown()
            realScope.cancel()
        }
    }

    /** Connects one generation, tears it down by cancelling its collector, and reads its signal. */
    private suspend fun TestScope.disconnectConfirmedAfterTeardown(service: FakeService): Boolean {
        val l = launcher(FakeFactory(service))
        val emitted = CompletableDeferred<AdbHostLauncher.ConnectionWrapper<AdbConnection, AdbConnection>>()
        val job = launch { l.connect().collect { emitted.complete(it) } }
        runCurrent()
        service.connect()
        runCurrent()
        val connection = emitted.await()

        job.cancelAndJoin()
        return connection.disconnectConfirmed.await()
    }

    @Test fun `disconnectConfirmed when the service ends after the stop returned`() = runTest {
        val service = FakeService(onStop = { this@runTest.launch { end() } })

        disconnectConfirmedAfterTeardown(service) shouldBe true
        events shouldContainInOrder listOf("stop", "ended")
    }

    @Test fun `disconnectConfirmed when the service ends before the stop returned`() = runTest {
        val service = FakeService(onStop = {
            end()
            ended.await()
        })

        disconnectConfirmedAfterTeardown(service) shouldBe true
    }

    @Test fun `disconnectConfirmed when the stop throws after the service ended`() = runTest {
        // A thrown stop still came back: the server answered instead of leaving the call in flight.
        val service = FakeService(onStop = {
            end()
            throw IllegalStateException("stop boom")
        })

        disconnectConfirmedAfterTeardown(service) shouldBe true
    }

    @Test fun `not disconnectConfirmed when the stop does not return in time, even if the service ends`() = runTest {
        val service = FakeService(onStop = {
            end()
            awaitCancellation()
        })

        disconnectConfirmedAfterTeardown(service) shouldBe false
        events shouldContain "ended"
    }

    @Test fun `not disconnectConfirmed when the service does not end in time`() = runTest {
        val service = FakeService(onStop = {
            this@runTest.launch {
                delay(60 * 1000L)
                end()
            }
        })

        disconnectConfirmedAfterTeardown(service) shouldBe false
    }

    @Test fun `disconnectConfirmed after an unexpected disconnect once the stop returned`() = runTest {
        val service = FakeService(onStop = { })
        val l = launcher(FakeFactory(service))
        val emitted = CompletableDeferred<AdbHostLauncher.ConnectionWrapper<AdbConnection, AdbConnection>>()
        val job = launch { runCatching { l.connect().collect { emitted.complete(it) } } }
        runCurrent()
        service.connect()
        runCurrent()

        service.end()
        advanceUntilIdle()

        emitted.await().disconnectConfirmed.await() shouldBe true
        events shouldContainInOrder listOf("ended", "stop")
        job.cancelAndJoin()
    }

    /**
     * The production factory over a fake connection, with the handshake faked: the real one needs a
     * live AIDL binder.
     */
    private fun defaultFactoryWithFakeHandshake(
        currentServer: () -> AdbServerConnection?,
        serviceArgs: (KClass<out AdbConnection>, AdbHostOptions) -> UserServiceArgs = { _, options ->
            adbHostServiceArgs(mockk(), options, versionCode = 1)
        },
    ): AdbUserServiceFactory {
        val production = DefaultAdbUserServiceFactory(currentServer, serviceArgs)
        val fake = FakeFactory(service = null)
        return object : AdbUserServiceFactory {
            override fun <Host : AdbConnection> create(
                hostClass: KClass<Host>,
                options: AdbHostOptions,
            ): AdbUserService? = production.create(hostClass, options)

            override fun <Service : IInterface, Host : AdbConnection> handshake(
                binder: IBinder,
                serviceClass: KClass<Service>,
                options: AdbHostOptions,
            ): Pair<Service, Host> = fake.handshake(binder, serviceClass, options)
        }
    }

    /** Records what reaches the SDK; its user service completes on a stop or once replaced. */
    private class FakeServerConnection : AdbServerConnection {
        val bindArgs = mutableListOf<UserServiceArgs>()
        val stopArgs = mutableListOf<UserServiceArgs>()
        private val connected = Channel<IBinder>(Channel.UNLIMITED)

        fun connect(binder: IBinder = mockk()) {
            connected.trySend(binder)
        }

        fun replaced() {
            connected.close()
        }

        override val backend: AdbBackend = AdbBackend.PORTER
        override val permission: Flow<AdbPermissionState> = emptyFlow()
        override suspend fun checkPermission(): AdbPermissionState = AdbPermissionState.Granted
        override suspend fun requestPermission(): AdbPermissionState = AdbPermissionState.Granted

        override fun userService(args: UserServiceArgs): Flow<IBinder> = flow {
            bindArgs += args
            for (binder in connected) emit(binder)
        }

        override suspend fun stopUserService(args: UserServiceArgs) {
            stopArgs += args
            connected.close()
        }
    }
}
