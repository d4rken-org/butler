package eu.darken.butler.common.adb.service.internal

import android.os.IInterface
import dagger.Reusable
import eu.darken.butler.common.adb.AdbConnectTimeoutException
import eu.darken.butler.common.adb.AdbException
import eu.darken.butler.common.adb.service.AdbHostOptions
import eu.darken.butler.common.adb.shizuku.ShizukuWrapper
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * The SDK touchpoints (user service collection and stop) are behind an injectable seam
 * ([AdbUserServiceFactory]) so this orchestration, especially the finally-block teardown, is
 * unit-testable. See AdbHostLauncherSeam.kt.
 */
@Reusable
class AdbHostLauncher(
    private val serviceFactory: AdbUserServiceFactory,
    private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
) {

    // DI uses the real SDK-backed seam; the primary constructor lets tests inject a fake.
    @Inject constructor(
        @AppScope appScope: CoroutineScope,
        dispatcherProvider: DispatcherProvider,
        shizukuWrapper: ShizukuWrapper,
    ) : this(DefaultAdbUserServiceFactory(shizukuWrapper::currentServer), appScope, dispatcherProvider)

    @OptIn(DelicateCoroutinesApi::class) // isClosedForSend, to skip the close() on intentional teardown
    fun <Service : IInterface, Host : AdbConnection> createConnection(
        serviceClass: KClass<Service>,
        hostClass: KClass<Host>,
        options: AdbHostOptions,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        unbindTimeoutMs: Long = UNBIND_TIMEOUT_MS,
    ): Flow<ConnectionWrapper<Service, Host>> = callbackFlow {
        val service = serviceFactory.create(hostClass = hostClass, options = options)
            ?: throw AdbException("No ADB access server is connected")

        // Completed only once a connection was actually handed downstream, this is what the
        // connect-watchdog below waits for.
        val ready = CompletableDeferred<Unit>()

        // Attempt-scoped answer to "is this generation's host actually gone?", handed to whoever
        // wants to bind a replacement. The teardown below is bounded, so a finished producer coroutine
        // says nothing about the server having stopped the service. Always completed by that teardown
        // (see its finally), so awaiting it can't hang a caller.
        val disconnectConfirmed = CompletableDeferred<Boolean>()

        // Completed when the binder collection ends by itself: the service died, or the server
        // connection it runs on was replaced or lost. Never by our own cancellation of it.
        val serviceEnded = CompletableDeferred<Unit>()

        // Set when the bind itself failed, which leaves no service of ours to stop.
        val bindFailed = AtomicBoolean(false)

        // Started before the collection: a bind that never connects must still release everyone
        // waiting on this flow.
        launch {
            if (withTimeoutOrNull(connectTimeoutMs) { ready.await() } == null) {
                log(TAG, WARN) { "User service did not connect within ${connectTimeoutMs}ms, closing" }
                // Residual epsilon race: a send() completing concurrently with the deadline can tear
                // down a connection that just came up. CompletableDeferred + withTimeoutOrNull narrows
                // that window but can't close it; the next acquire re-binds.
                close(AdbConnectTimeoutException("ADB user service did not connect within ${connectTimeoutMs}ms"))
            }
        }

        // Runs OUTSIDE the producer scope, so it outlives the producer's cancellation: teardown below
        // sends the stop while this collection is still active, and its completion is how we see the
        // service actually end. Cancelling it only drops the binding, it does not stop the service.
        val collection = appScope.launch(dispatcherProvider.IO) {
            try {
                service.binders().collect { binder ->
                    log(TAG) { "User service connected (binder=$binder)" }
                    // Off the collector: the handshake does binder transactions, and the collection
                    // has to stay free to notice the service ending meanwhile.
                    this@callbackFlow.launch {
                        try {
                            log(TAG) { "Handshaking with the user service, options=$options" }
                            val (userConnection, baseConnection) = serviceFactory.handshake<Service, Host>(
                                binder = binder,
                                serviceClass = serviceClass,
                                options = options,
                            )
                            log(TAG) { "User service handshake done -> $userConnection" }
                            send(ConnectionWrapper(userConnection, baseConnection, disconnectConfirmed))
                            ready.complete(Unit)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log(TAG, WARN) { "User service handshake failed: ${e.asLog()}" }
                            close(AdbException("ADB user service handshake failed", e))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "User service bind failed: ${e.asLog()}" }
                bindFailed.set(true)
                serviceEnded.complete(Unit)
                close(e)
                return@launch
            }
            serviceEnded.complete(Unit)
            // The collection ending while the flow is live is an UNEXPECTED disconnect (service died,
            // server replaced or gone). Close the flow so the SharedResource generation tears down and
            // the next get() re-binds, instead of handing out a dead connection during the keep-alive
            // window. On intentional teardown the channel is already closed, so this is a no-op.
            if (!isClosedForSend) {
                log(TAG, WARN) { "ADB user service disconnected unexpectedly, closing connection" }
                close(AdbException("ADB user service disconnected"))
            }
        }

        try {
            log(TAG) { "Waiting for flow to close" }
            awaitClose { log(TAG) { "awaitClose() reached, flow is closing…" } }
        } finally {
            // Runs on cancellation too. Cleanup lives in the finally (not only in awaitClose) so a throw
            // before awaitClose can't leak the service.
            withContext(NonCancellable) {
                // Stays false unless this teardown can prove the host is gone. A stop we stopped
                // waiting for can still land later, and since the server keys it on the service args,
                // what it hits then can be a replacement bound meanwhile. Completed in the finally, so
                // nobody awaiting it can hang.
                var confirmed = false
                try {
                    if (bindFailed.get()) {
                        log(TAG) { "Bind failed, nothing to stop" }
                    } else {
                        log(TAG) { "Stopping ADB user service…" }
                        val stopReturned = stopBounded(service, unbindTimeoutMs)
                        // Still worth waiting after a timed-out stop: the server may have carried it out
                        // while its reply was still on the way.
                        val ended = withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { serviceEnded.await() } != null
                        if (!ended) log(TAG, WARN) { "User service did not end within ${DISCONNECT_TIMEOUT_MS}ms" }
                        // Both halves, because they answer different halves of the question: the stop
                        // returning means the server took it, the collection ending means the service
                        // is gone.
                        confirmed = stopReturned && ended
                        log(TAG) { "ADB user service teardown finished (confirmed=$confirmed)" }
                    }
                } finally {
                    collection.cancel()
                    disconnectConfirmed.complete(confirmed)
                }
            }
        }
    }

    /**
     * Sends the stop detached and waits at most [timeoutMs] for it, true when it returned in time, a
     * thrown failure included: the server answered instead of leaving the call in flight.
     *
     * Detached, like everything that talks to a server that may be wedged: an unbounded wait here
     * would pin every collector, since collection of a callbackFlow awaits its producer.
     */
    private suspend fun stopBounded(service: AdbUserService, timeoutMs: Long): Boolean {
        val stop = appScope.async(dispatcherProvider.IO) {
            try {
                service.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "stopUserService() failed: ${e.asLog()}" }
            }
        }
        return try {
            val returned = withTimeoutOrNull(timeoutMs) { stop.await() } != null
            if (!returned) log(TAG, WARN) { "stopUserService() did not return within ${timeoutMs}ms" }
            returned
        } catch (e: CancellationException) {
            // @AppScope already cancelled (shutdown): the stop never ran, and not being able to start
            // it is no reason to skip the rest of the teardown.
            log(TAG, WARN) { "Detached stop could not run: ${e.asLog()}" }
            false
        } finally {
            stop.cancel()
        }
    }

    data class ConnectionWrapper<Service : IInterface, Host : AdbConnection>(
        val service: Service,
        val host: Host,
        /**
         * Scoped to THIS connection attempt: completes with true only once its teardown both got its
         * stop back within the timeout and saw the service's binder collection end. False means a stop
         * may still be in flight, and since the server keys it on the service args, a replacement bound
         * now could be the one it hits. Anyone rebinding after a teardown has to wait for this and
         * honour it.
         */
        val disconnectConfirmed: Deferred<Boolean>,
    )

    companion object {
        private val TAG = logTag("ADB", "Host", "Launcher")

        // How long to wait for the user service to actually end after the stop before giving up,
        // bounded so teardown can't hang.
        private const val DISCONNECT_TIMEOUT_MS = 500L

        // How long to wait for the user service to connect after binding. Generous: a cold AdbHost
        // start is a multi-second affair (see AdbServiceClient's keep-alive rationale). AppOpsNext uses
        // 12s for the same probe against the upstream Shizuku defect where the bind returns but the
        // connection callback never fires (MediaTek/HyperOS, Shizuku 13.6.0).
        internal const val CONNECT_TIMEOUT_MS = 15 * 1000L

        // How long teardown waits for stopUserService() before moving on without it. Short, unlike the
        // probe timeouts around it, because it answers a different question: a probe that gives up too
        // early reports a working server as broken, while this one only decides how long a failing
        // teardown may hold the flow open. Still generous against a healthy server's round-trip, since
        // the wedge this guards against shows up on low-end hardware under memory pressure, and giving
        // up early is not free either: it is what leaves the stop in flight (see disconnectConfirmed).
        internal const val UNBIND_TIMEOUT_MS = 2 * 1000L
    }
}
