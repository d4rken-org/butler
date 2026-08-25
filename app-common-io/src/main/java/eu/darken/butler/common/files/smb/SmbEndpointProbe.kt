package eu.darken.butler.common.files.smb

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.network.NetworkStateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where a stored network location resolves to, and whether its port answers.
 *
 * Address and reachability are separate: a host that resolves but whose port refuses still has an
 * address worth showing next to "unavailable".
 */
data class SmbEndpointState(
    val address: String? = null,
    val reachability: Reachability = Reachability.CHECKING,
) {
    enum class Reachability {
        CHECKING,
        REACHABLE,
        UNREACHABLE,
    }
}

/**
 * Resolves and TCP-pings the stored network locations so the Network list can show where a server is
 * and whether it answers.
 *
 * No SMB negotiation and no authentication happen here, a probe is a DNS lookup plus a connect that
 * is closed again, so it costs the server no login attempt.
 *
 * Probes run on the application scope and are never structured children of whoever asked for them:
 * neither [InetAddress.getAllByName] nor [Socket.connect] becomes cancellable by running on an IO
 * dispatcher, so a probe that was a child of a loader would make the loader's teardown wait for a
 * DNS lookup to return. Leaving the Network view therefore only stops the observation.
 */
@Singleton
class SmbEndpointProbe @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val resolver: Resolver,
    private val connector: Connector,
    private val clock: Clock,
    private val networkStateProvider: NetworkStateProvider,
) {

    /** Injected so multi-address fallback can be driven without a name server. */
    fun interface Resolver {
        /** @throws java.net.UnknownHostException */
        fun resolve(host: String): List<InetAddress>
    }

    /** Injected so timeouts and refusals can be driven without a server. */
    interface Connector {
        /** Opens a connection and closes it again, throwing if it cannot be established. */
        suspend fun connect(address: InetAddress, port: Int, timeout: Duration)
    }

    /** Injected so cache expiry is reachable without waiting for it. */
    fun interface Clock {
        fun now(): Instant
    }

    private val tag = logTag("SMB", "EndpointProbe")

    private data class Endpoint(val host: String, val port: Int)

    private data class CacheEntry(val state: SmbEndpointState, val probedAt: Instant)

    private data class InFlightProbe(val epoch: Int, val deferred: Deferred<SmbEndpointState>)

    private val mutex = Mutex()
    private val cache = mutableMapOf<Endpoint, CacheEntry>()
    private val inFlight = mutableMapOf<Endpoint, InFlightProbe>()

    /** What each id last published a state for, so only an edited host resets its row to unknown. */
    private val probedEndpoints = mutableMapOf<Uuid, Endpoint>()

    /** Bumped on every connectivity change, results from an earlier epoch describe another network. */
    private var connectivityEpoch = 0

    @Volatile private var watched: List<SmbLocation> = emptyList()

    private val _states = MutableStateFlow<Map<Uuid, SmbEndpointState>>(emptyMap())
    val states: StateFlow<Map<Uuid, SmbEndpointState>> = _states.asStateFlow()

    init {
        // Expiry and connectivity only matter while somebody is looking, and the connectivity
        // callback should not stay registered for the life of the app either.
        _states.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .flatMapLatest { isWatched ->
                if (!isWatched) {
                    emptyFlow()
                } else {
                    merge(
                        flowOf(Trigger.RESUBSCRIBED),
                        expiryTicks().map { Trigger.EXPIRY },
                        networkStateProvider.networkState.drop(1).map { Trigger.CONNECTIVITY },
                    )
                }
            }
            .onEach { trigger ->
                log(tag) { "Re-probing after $trigger" }
                if (trigger != Trigger.EXPIRY) {
                    mutex.withLock {
                        cache.clear()
                        connectivityEpoch++
                    }
                }
                probeAll(watched, force = false)
            }
            .launchIn(appScope)
    }

    private enum class Trigger {
        EXPIRY,
        CONNECTIVITY,

        /**
         * Nothing watches connectivity while the list is off screen, so the gap counts as a change
         * rather than being compared against anything: an unnoticed hop to another network and back
         * looks identical to no hop at all.
         */
        RESUBSCRIBED,
    }

    private fun expiryTicks() = flow {
        while (true) {
            delay(EXPIRY_CHECK_INTERVAL)
            emit(Unit)
        }
    }

    /**
     * Publishes a state for every given location, probing the ones whose cached result is missing or
     * stale. [force] skips the cache, which is what an explicit user refresh does.
     */
    fun probe(locations: Collection<SmbLocation>, force: Boolean = false) {
        log(tag) { "probe(${locations.size} locations, force=$force)" }
        watched = locations.toList()
        appScope.launch { probeAll(locations, force) }
    }

    private suspend fun probeAll(locations: Collection<SmbLocation>, force: Boolean) = coroutineScope {
        val known = watched.map { it.id }.toSet()
        _states.update { states -> states.filterKeys { it in known } }
        locations.forEach { location -> launch { probeLocation(location, force) } }
    }

    /**
     * What an id stands for right now. [watched] is assigned before a pass is even launched, so it,
     * unlike anything a pass itself writes, cannot be walked backwards by a pass that lost the race.
     */
    private fun currentEndpoint(id: Uuid): Endpoint? = watched
        .firstOrNull { it.id == id }
        ?.let { Endpoint(it.host, it.port) }

    private suspend fun probeLocation(location: SmbLocation, force: Boolean) {
        val endpoint = Endpoint(location.host, location.port)

        val probe = mutex.withLock {
            if (currentEndpoint(location.id) != endpoint) {
                log(tag, VERBOSE) { "Skipping $endpoint, ${location.id} does not stand for it anymore" }
                return
            }
            if (force) cache.remove(endpoint)
            val cached = cache[endpoint]
            if (cached != null && clock.now() - cached.probedAt < CACHE_TTL) {
                probedEndpoints[location.id] = endpoint
                publish(location.id, cached.state)
                return
            }
            // A second view asking for the same server joins the running probe instead of opening
            // another connection to it, unless that probe ran on the network we just left.
            if (probedEndpoints[location.id] != endpoint) {
                probedEndpoints[location.id] = endpoint
                publish(location.id, SmbEndpointState())
            }
            inFlight[endpoint]?.takeIf { it.epoch == connectivityEpoch }
                ?: InFlightProbe(
                    epoch = connectivityEpoch,
                    deferred = appScope.async(dispatcherProvider.IO) { runProbe(endpoint) },
                ).also { inFlight[endpoint] = it }
        }

        val state = try {
            probe.deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Probe of $endpoint failed: ${e.asLog()}" }
            SmbEndpointState(reachability = SmbEndpointState.Reachability.UNREACHABLE)
        }

        mutex.withLock {
            if (inFlight[endpoint]?.deferred === probe.deferred) inFlight.remove(endpoint)
            // The host may have been edited and the network may have changed while this ran, either
            // makes the result describe something else than what the id stands for now.
            val isCurrent = probe.epoch == connectivityEpoch && currentEndpoint(location.id) == endpoint
            if (!isCurrent) {
                log(tag, VERBOSE) { "Dropping outdated result for $endpoint: $state" }
                return@withLock
            }
            cache[endpoint] = CacheEntry(state, clock.now())
            publish(location.id, state)
        }
    }

    private fun publish(id: Uuid, state: SmbEndpointState) {
        _states.update { it + (id to state) }
    }

    private suspend fun runProbe(endpoint: Endpoint): SmbEndpointState {
        // A blocking name lookup offers no suspension point, so awaiting it from a coroutine of its
        // own is the only way the deadline below can fire while the resolver is still stuck.
        val resolution = appScope.async(dispatcherProvider.IO) { resolver.resolve(endpoint.host) }
        var firstResolvedAddress: String? = null

        return try {
            withTimeoutOrNull(OVERALL_TIMEOUT) {
                val addresses = try {
                    resolution.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(tag, VERBOSE) { "${endpoint.host} does not resolve: ${e.asLog()}" }
                    emptyList<InetAddress>()
                }
                if (addresses.isEmpty()) return@withTimeoutOrNull SmbEndpointState(
                    reachability = SmbEndpointState.Reachability.UNREACHABLE,
                )
                firstResolvedAddress = addresses.first().hostAddress

                // getByName alone would be enough for the address, but it can hand back an IPv6 address
                // that is unreachable on a host that answers perfectly well over IPv4.
                addresses.forEach { address ->
                    try {
                        connector.connect(address, endpoint.port, ATTEMPT_TIMEOUT)
                        return@withTimeoutOrNull SmbEndpointState(
                            address = address.hostAddress,
                            reachability = SmbEndpointState.Reachability.REACHABLE,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(tag, VERBOSE) { "${address.hostAddress}:${endpoint.port} did not answer: ${e.asLog()}" }
                    }
                }

                SmbEndpointState(
                    address = firstResolvedAddress,
                    reachability = SmbEndpointState.Reachability.UNREACHABLE,
                )
            } ?: SmbEndpointState(
                address = firstResolvedAddress,
                reachability = SmbEndpointState.Reachability.UNREACHABLE,
            )
        } finally {
            resolution.cancel()
        }
    }

    companion object {
        /** One attempt per resolved address, the whole probe is bounded by [OVERALL_TIMEOUT]. */
        private val ATTEMPT_TIMEOUT = 3.seconds
        private val OVERALL_TIMEOUT = 8.seconds
        private val CACHE_TTL = 60.seconds
        private val EXPIRY_CHECK_INTERVAL = 15.seconds
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SmbEndpointProbeModule {

    @Provides
    @Singleton
    fun resolver(): SmbEndpointProbe.Resolver = SmbEndpointProbe.Resolver { host ->
        InetAddress.getAllByName(host).toList()
    }

    @Provides
    @Singleton
    fun connector(): SmbEndpointProbe.Connector = object : SmbEndpointProbe.Connector {
        override suspend fun connect(address: InetAddress, port: Int, timeout: Duration) {
            val socket = Socket()
            // A blocking connect only ends when the socket is closed, so cancellation has to do
            // that from the outside instead of waiting for the attempt to time out.
            val closeOnCancel = currentCoroutineContext().job.invokeOnCompletion { runCatching { socket.close() } }
            try {
                socket.connect(InetSocketAddress(address, port), timeout.inWholeMilliseconds.toInt())
            } finally {
                closeOnCancel.dispose()
                runCatching { socket.close() }
            }
        }
    }

    @Provides
    @Singleton
    fun clock(): SmbEndpointProbe.Clock = SmbEndpointProbe.Clock { kotlin.time.Clock.System.now() }
}
