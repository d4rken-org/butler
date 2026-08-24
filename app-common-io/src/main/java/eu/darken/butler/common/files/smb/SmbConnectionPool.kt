package eu.darken.butler.common.files.smb

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Keeps one SMB session per (location, credential generation) alive across operations.
 *
 * Every operation and every stream or file handle handed out holds a lease on the generation it
 * came from. A generation whose transport died is evicted immediately, but its session is only
 * closed once the last lease is returned, so an in-flight read never has its share pulled out from
 * under it.
 */
@Singleton
class SmbConnectionPool @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val locationManager: SmbLocationManager,
    private val credentialStore: SmbCredentialStore,
    private val clientFactory: SmbClientFactory,
    private val dialectProbe: SmbDialectProbe,
) {

    /** A live share plus the lease keeping it alive. Closing it twice is a no-op. */
    class Lease(
        val location: SmbLocation,
        val share: DiskShare,
        private val onRelease: () -> Unit,
    ) : AutoCloseable {
        private var released = false

        @Synchronized
        override fun close() {
            if (released) return
            released = true
            onRelease()
        }
    }

    private data class Key(val locationId: Uuid, val credentialVersion: Int)

    private class Generation(
        val key: Key,
        val location: SmbLocation,
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    ) {
        var leases: Int = 0
        var stale: Boolean = false
        var idleSince: Long = Clock.System.now().toEpochMilliseconds()
    }

    private val lock = Mutex()
    private val generations = mutableMapOf<Key, Generation>()

    init {
        credentialStore.evictions
            .onEach { evict(it) }
            .launchIn(appScope)

        appScope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL)
                trimIdle()
            }
        }
    }

    /**
     * Runs [block] against a live share.
     *
     * @param retryOnTransportLoss only for operations that are safe to repeat. A create, delete,
     * rename or write may have reached the server before the transport died, replaying it could
     * destroy the wrong thing or duplicate work, so those pass false and surface the failure.
     */
    suspend fun <R> use(
        path: SmbPath,
        retryOnTransportLoss: Boolean,
        block: suspend (Lease) -> R,
    ): R {
        try {
            return acquire(path.locationId).use { block(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!retryOnTransportLoss || !SmbStatusMapper.isTransportLost(e)) throw e
            log(TAG, WARN) { "Transport lost, reconnecting once: ${e.asLog()}" }
        }

        evict(path.locationId)
        return acquire(path.locationId).use { block(it) }
    }

    /**
     * Leases a share the caller keeps open (streams, file handles). The caller MUST close the
     * returned lease, even when its own close throws.
     */
    suspend fun acquire(locationId: Uuid): Lease {
        val location = locationManager.get(locationId)
            ?: throw SmbUnreachableException(locationId.toString())

        val key = Key(locationId, location.credentialVersion)

        repeat(CONNECT_ATTEMPTS) {
            val generation = lock.withLock {
                val cached = generations[key]?.takeIf { !it.stale && it.connection.isConnected }
                when {
                    cached == null -> null
                    // The key only covers the credential generation, so an edited host, port, share,
                    // base path or domain would otherwise keep being served from the old endpoint.
                    !cached.location.hasSameEndpoint(location) -> {
                        generations.remove(key)
                        markStale(cached)
                        null
                    }

                    else -> cached
                }
            } ?: connect(location, key)

            val leased = synchronized(generation) {
                if (generation.stale) {
                    false
                } else {
                    generation.leases++
                    true
                }
            }

            if (leased) {
                return Lease(generation.location, generation.share) { release(generation) }
            }
        }

        throw SmbUnreachableException(location.endpointLabel)
    }

    private suspend fun connect(location: SmbLocation, key: Key): Generation {
        val endpoint = location.endpointLabel

        val credential = when (location.authType) {
            SmbLocation.AuthType.GUEST -> null
            SmbLocation.AuthType.PASSWORD -> credentialStore.resolve(location)
        }

        // The context keeps its own copy of the password, so ours can go immediately.
        var authContext: AuthenticationContext? = try {
            when (credential) {
                null -> AuthenticationContext.guest()
                else -> AuthenticationContext(credential.username, credential.password, credential.domain)
            }
        } finally {
            credential?.wipe()
        }

        val client = clientFactory.create(CONFIG)
        val fresh = try {
            val connection = client.connect(location.host, location.port)
            val session = connection.authenticate(authContext!!)
            val share = session.connectShare(location.share) as? DiskShare
                ?: throw SmbShareNotFoundException(endpoint, location.share)
            Generation(key, location, client, connection, session, share)
        } catch (e: Exception) {
            runCatching { client.close() }
            throw mapConnectFailure(e, location, endpoint)
        } finally {
            // Nothing past the session setup needs the password, don't hold it any longer
            authContext = null
        }

        log(TAG, INFO) { "Connected to $endpoint (credential generation ${key.credentialVersion})" }

        return lock.withLock {
            val existing = generations[key]?.takeIf { !it.stale && it.connection.isConnected }
            if (existing != null) {
                // Raced another caller onto the same endpoint, keep theirs
                closeQuietly(fresh)
                existing
            } else {
                generations.remove(key)?.let { markStale(it) }
                generations[key] = fresh
                fresh
            }
        }
    }

    /** Everything the session is bound to that [Key] does not cover. */
    private fun SmbLocation.hasSameEndpoint(other: SmbLocation): Boolean = host == other.host &&
        port == other.port &&
        share == other.share &&
        basePath == other.basePath &&
        domain == other.domain

    /**
     * An SMB1-only server hangs up on our negotiate, which is indistinguishable from an unreachable
     * host until it is asked with multi-protocol negotiation.
     */
    private fun mapConnectFailure(error: Throwable, location: SmbLocation, endpoint: String): Throwable {
        val mapped = SmbStatusMapper.mapConnect(error, endpoint, location.share)
        if (mapped !is SmbUnreachableException || !dialectProbe.isWorthProbing(error)) return mapped
        if (!dialectProbe.isSmb1Only(location.host, location.port)) return mapped
        return SmbDialectNotSupportedException(endpoint, error)
    }

    /** Drops every generation of a location, e.g. after its credential changed. */
    suspend fun evict(locationId: Uuid) {
        val dropped = lock.withLock {
            generations.keys
                .filter { it.locationId == locationId }
                .toList()
                .mapNotNull { generations.remove(it) }
        }
        if (dropped.isNotEmpty()) log(TAG) { "Evicting ${dropped.size} generation(s) of $locationId" }
        dropped.forEach { markStale(it) }
    }

    /**
     * Drops every session, e.g. when the last user of the gateway lets go. The pool itself stays
     * usable: the next operation reconnects instead of failing for the rest of the process.
     */
    suspend fun close() {
        val dropped = lock.withLock {
            generations.values.toList().also { generations.clear() }
        }
        dropped.forEach { markStale(it) }
    }

    /** @return whether any generation is left. Internal so the idle policy can be tested. */
    internal suspend fun trimIdle(now: Long = Clock.System.now().toEpochMilliseconds()): Boolean {
        val dropped = mutableListOf<Generation>()
        val remaining = lock.withLock {
            generations.entries
                .filter { (_, gen) ->
                    synchronized(gen) { gen.leases == 0 && now - gen.idleSince > IDLE_TIMEOUT.inWholeMilliseconds }
                }
                .map { it.key }
                .forEach { key -> generations.remove(key)?.let { dropped.add(it) } }
            generations.isNotEmpty()
        }
        dropped.forEach { closeQuietly(it) }
        return remaining
    }

    /** Called from stream/handle close, which cannot suspend. */
    private fun release(generation: Generation) = synchronized(generation) {
        generation.leases--
        generation.idleSince = Clock.System.now().toEpochMilliseconds()
        if (generation.leases <= 0 && generation.stale) closeQuietly(generation)
    }

    private fun markStale(generation: Generation) = synchronized(generation) {
        generation.stale = true
        if (generation.leases <= 0) closeQuietly(generation)
    }

    private fun closeQuietly(generation: Generation) {
        log(TAG, VERBOSE) { "Closing session for ${generation.location.endpointLabel}" }
        runCatching { generation.share.close() }
        runCatching { generation.session.close() }
        runCatching { generation.connection.close() }
        runCatching { generation.client.close() }
    }

    companion object {
        val TAG = logTag("SMB", "ConnectionPool")

        private val IDLE_TIMEOUT = 60.seconds
        private val IDLE_CHECK_INTERVAL = 30.seconds
        private const val CONNECT_ATTEMPTS = 3

        internal val CONFIG: SmbConfig = SmbConfig.builder()
            .withSocketFactory(SmbSocketFactory())
            .withSoTimeout(SmbSocketFactory.SOCKET_TIMEOUT_MS)
            .withTimeout(SmbSocketFactory.SOCKET_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .withDfsEnabled(false)
            .build()
    }
}
