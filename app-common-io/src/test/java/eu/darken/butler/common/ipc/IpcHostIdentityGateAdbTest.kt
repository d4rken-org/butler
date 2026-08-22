package eu.darken.butler.common.ipc

import android.os.IBinder
import android.os.IInterface
import eu.darken.butler.common.adb.isAdbConnectTimeout
import eu.darken.butler.common.adb.service.AdbHostOptions
import eu.darken.butler.common.adb.service.internal.AdbConnection
import eu.darken.butler.common.adb.service.internal.AdbHostLauncher
import eu.darken.butler.common.adb.service.internal.ShizukuUserService
import eu.darken.butler.common.adb.service.internal.ShizukuUserServiceFactory
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * and Shizuku's teardown have to line up: the launcher's unbind is detached under a timeout, so its
 * producer coroutine can finish with the removal still in flight, and rebinding then risks the late
 * `remove=true` unbind taking out the replacement instead. Shizuku is replaced via the launcher's
 * seam (AdbHostLauncherSeam.kt).
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
    private val connectCallbacks = LinkedBlockingQueue<(IBinder?) -> Unit>()
    private val binder = mockk<IBinder>()

    private inner class FakeFactory(private val service: ShizukuUserService) : ShizukuUserServiceFactory {

        override fun apiVersion(): Int = 11

        override fun <Host : AdbConnection> create(
            hostClass: KClass<Host>,
            options: AdbHostOptions,
            onConnected: (IBinder?) -> Unit,
            onDisconnected: () -> Unit,
        ): ShizukuUserService {
            connectCallbacks.put(onConnected)
            return service
        }

        @Suppress("UNCHECKED_CAST")
        override fun <Service : IInterface, Host : AdbConnection> handshake(
            binder: IBinder?,
            serviceClass: KClass<Service>,
            options: AdbHostOptions,
        ): Pair<Service, Host> = (mockk<AdbConnection>() as Service) to (mockk<AdbConnection>() as Host)
    }

    /**
     * Stands in for Shizuku firing onServiceConnected, for however many generations get bound — which
     * is the thing under test, so it can't be a fixed number of hand-fired callbacks.
     */
    private fun startConnectPump(): Thread = Thread {
        try {
            while (!Thread.currentThread().isInterrupted) {
                connectCallbacks.poll(100, TimeUnit.MILLISECONDS)?.invoke(binder)
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

    /** bind() runs detached from the flow, so its count is only stable once it has caught up. */
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

    private fun launcher(service: ShizukuUserService, scope: CoroutineScope) = AdbHostLauncher(
        serviceFactory = FakeFactory(service),
        appScope = scope,
        // Real dispatchers: the wedge below blocks an actual thread, virtual time can't model that.
        dispatcherProvider = TestDispatcherProvider(Dispatchers.IO),
    )

    @Test fun `a mismatch does not rebind while the stale unbind is still in flight`() = runTest(
        timeout = 10.seconds,
    ) {
        val unbindEntered = CompletableDeferred<Unit>()
        val unbindWedge = CountDownLatch(1)
        val service = object : ShizukuUserService {
            override fun bind() {
                binds.incrementAndGet()
            }

            override fun unbind() {
                unbindEntered.complete(Unit)
                unbindWedge.await() // blocks the thread, unaffected by coroutine cancellation
            }

            override suspend fun awaitDisconnect() {}
        }
        val realScope = CoroutineScope(SupervisorJob())
        val hostLauncher = launcher(service, realScope)
        val pump = startConnectPump()

        try {
            val result = realScope.async(Dispatchers.Default) {
                runCatching { hostLauncher.gated(stale.encode(), ours.encode(), unbindTimeoutMs = 250L).first() }
            }

            withContext(Dispatchers.Default) {
                awaitOrFail("unbind() to be entered") { unbindEntered.await() }

                // Teardown gives up on the wedged unbind, and an unconfirmed teardown is not a base to
                // rebind on: the mismatch reaches the caller instead.
                val error = awaitOrFail("the collector to be released") { result.await() }.exceptionOrNull()
                error.shouldBeInstanceOf<IpcContractMismatchException>()
            }

            // The whole point: no second generation for the in-flight unbind to remove.
            binds.get() shouldBe 1
        } finally {
            unbindWedge.countDown()
            pump.interrupt()
            realScope.cancel()
        }
    }

    @Test fun `a mismatch rebinds once the teardown is confirmed`() = runTest(timeout = 10.seconds) {
        val service = object : ShizukuUserService {
            override fun bind() {
                binds.incrementAndGet()
            }

            override fun unbind() {}

            override suspend fun awaitDisconnect() {}
        }
        val realScope = CoroutineScope(SupervisorJob())
        val hostLauncher = launcher(service, realScope)
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
     * with the stale unbind still in flight. Wired with the predicate AdbServiceClient passes, so
     * this covers the layer the gate-only tests above cannot see.
     */
    @Test fun `a mismatch is not rebound by the shared resource retry either`() = runTest(timeout = 10.seconds) {
        val unbindEntered = CompletableDeferred<Unit>()
        val unbindWedge = CountDownLatch(1)
        val service = object : ShizukuUserService {
            override fun bind() {
                binds.incrementAndGet()
            }

            override fun unbind() {
                unbindEntered.complete(Unit)
                unbindWedge.await() // blocks the thread, unaffected by coroutine cancellation
            }

            override suspend fun awaitDisconnect() {}
        }
        val realScope = CoroutineScope(SupervisorJob())
        val hostLauncher = launcher(service, realScope)

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
            // The predicate AdbServiceClient installs.
            isRetryableStartupFailure = { !it.isAdbConnectTimeout() && it !is IpcContractMismatchException },
        )
        var pump: Thread? = null

        try {
            withContext(Dispatchers.Default) {
                val creator = realScope.async(Dispatchers.IO) { runCatching { sharedResource.get() } }
                // Generation 1 is installed and its source is running, but nothing has answered its
                // connect callback yet, so it cannot have produced a value.
                awaitBinds(1)

                val reuser = realScope.async(Dispatchers.IO) { runCatching { sharedResource.get() } }
                awaitOrFail("the second caller to latch onto generation 1") { reuserLatched.await() }

                // Only now let the host connect, mismatch, and wedge its unbind.
                pump = startConnectPump()
                awaitOrFail("unbind() to be entered") { unbindEntered.await() }

                awaitOrFail("the starting caller to be released") { creator.await() }
                    .exceptionOrNull().shouldBeInstanceOf<IpcContractMismatchException>()
                // The waiter must inherit the mismatch instead of retrying onto a fresh generation.
                awaitOrFail("the reusing caller to be released") { reuser.await() }
                    .exceptionOrNull().shouldBeInstanceOf<IpcContractMismatchException>()
            }

            // The whole point: no second generation for the in-flight unbind to remove.
            binds.get() shouldBe 1
        } finally {
            unbindWedge.countDown()
            pump?.interrupt()
            realScope.cancel()
            Logging.remove(capture)
            Bugs.isTrace = false
        }
    }
}

private const val SR_TAG = "gate-shared-resource"
