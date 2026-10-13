package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.isProForUi
import eu.darken.webdav.CertificatePolicy
import eu.darken.webdav.WebDavCredentials
import eu.darken.webdav.WebDavSession
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
import eu.darken.webdav.WebDavPath as ServerPath

/**
 * Keeps one WebDAV session per location alive across operations, bound to the location's URL,
 * username, authentication type, credential version and trust revision. A change to any of them
 * retires the session: operations still holding it finish, the next acquisition connects anew.
 *
 * Every operation and every stream or file handle handed out holds a lease on the generation it
 * came from. Retiring a generation waits for its last lease before disconnecting it.
 */
@Singleton
class WebDavClientPool @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val locationManager: WebDavLocationManager,
    private val credentialStore: WebDavCredentialStore,
    private val connectorFactory: WebDavConnectorFactory,
    private val upgradeRepo: UpgradeRepo,
) {

    /**
     * Network storage is a Pro feature. Both entry points below go through here, so this covers every
     * WebDAV byte - including the primitives [WebDavGateway] inherits from [WebDavFileSystemOps] rather
     * than overriding, and the streams and file handles a lease outlives.
     */
    private suspend fun requirePro() {
        if (!upgradeRepo.isProForUi()) throw WebDavProRequiredException()
    }

    /** A live session plus the lease keeping it alive. Closing it twice is a no-op. */
    class Lease(
        val location: WebDavLocation,
        val session: WebDavSession,
        private val onRelease: () -> Unit,
    ) : AutoCloseable {
        private var released = false

        fun serverPath(path: WebDavPath): ServerPath = ServerPath(path.segments)

        @Synchronized
        override fun close() {
            if (released) return
            released = true
            onRelease()
        }
    }

    /** Everything a session is bound to. Any difference retires it. */
    private data class Binding(
        val url: String,
        val username: String?,
        val authType: WebDavLocation.AuthType,
        val credentialVersion: Int,
        val trustRevision: Int,
    ) {
        constructor(location: WebDavLocation) : this(
            url = location.url,
            username = location.username,
            authType = location.authType,
            credentialVersion = location.credentialVersion,
            trustRevision = location.trustRevision,
        )
    }

    private class Generation(
        val location: WebDavLocation,
        val binding: Binding,
        val session: WebDavSession,
    ) {
        var leases: Int = 0
        var stale: Boolean = false
        var closed: Boolean = false
        var idleSince: Long = Clock.System.now().toEpochMilliseconds()
    }

    private val lock = Mutex()
    private val generations = mutableMapOf<Uuid, Generation>()

    /**
     * Bumped by [evict] and [close], both under [lock]. A connect that started under an older value
     * was overtaken by them, so its session is never published.
     */
    private val evictionEpochs = mutableMapOf<Uuid, Long>()
    private var closeEpoch = 0L

    private val connector by lazy { connectorFactory.create() }

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
     * Runs [block] against a live session.
     *
     * @param retryOnTransportLoss only for operations that are safe to repeat. A create, delete, move
     * or upload may have reached the server before the connection died, replaying it could destroy
     * the wrong thing or duplicate work, so those pass false and surface the failure.
     */
    suspend fun <R> use(
        path: WebDavPath,
        retryOnTransportLoss: Boolean,
        block: suspend (Lease) -> R,
    ): R {
        requirePro()
        try {
            return acquire(path.locationId).use { block(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!retryOnTransportLoss || !WebDavStatusMapper.isTransportLost(e)) throw e
            log(TAG, WARN) { "Connection lost, reconnecting once: ${e.asLog()}" }
        }

        evict(path.locationId)
        return acquire(path.locationId).use { block(it) }
    }

    /**
     * Leases a session the caller keeps open (streams, file handles). The caller MUST close the
     * returned lease, even when its own close throws.
     */
    suspend fun acquire(locationId: Uuid): Lease {
        requirePro()
        var endpoint = locationId.toString()

        repeat(CONNECT_ATTEMPTS) {
            val location = locationManager.get(locationId)
                ?: throw WebDavUnreachableException(locationId.toString())
            endpoint = location.endpointLabel
            val binding = Binding(location)

            var displaced: Generation? = null
            val reused = lock.withLock {
                val cached = generations[locationId]?.takeIf { !it.stale }
                when {
                    cached == null -> null
                    cached.binding != binding -> {
                        displaced = generations.remove(locationId)
                        null
                    }

                    else -> cached.takeIf { lease(it) }
                }
            }
            displaced?.let { markStale(it) }

            val generation = reused ?: connect(location, binding)

            if (generation != null) {
                return Lease(generation.location, generation.session) { release(generation) }
            }
        }

        throw WebDavUnreachableException(endpoint)
    }

    /**
     * Takes the first lease on a generation. Only ever called while [lock] is held, because
     * [trimIdle] collects on that same lock: checking and leasing in one go is what stops the idle
     * sweep from closing a session between it being picked and it reaching the caller.
     *
     * @return false if the generation went stale first, the caller then connects a fresh one.
     */
    private fun lease(generation: Generation): Boolean = synchronized(generation) {
        if (generation.stale) {
            false
        } else {
            generation.leases++
            true
        }
    }

    /**
     * @return the leased generation, or null if it went stale before it could be leased or the
     * location changed while connecting. The caller then starts over with the current location.
     */
    private suspend fun connect(location: WebDavLocation, binding: Binding): Generation? {
        val epoch = lock.withLock { epochOf(location.id) }

        val fresh = open(location, binding)

        log(TAG, INFO) {
            "Connected to ${location.endpointLabel} " +
                "(credential ${binding.credentialVersion}, trust ${binding.trustRevision})"
        }

        var redundant: Generation? = null
        var displaced: Generation? = null
        val leased = try {
            val latest = locationManager.get(location.id)
            lock.withLock {
                when {
                    // Edited, re-trusted, signed in again or evicted while this session was connecting.
                    latest == null || Binding(latest) != binding || epochOf(location.id) != epoch -> {
                        log(TAG, INFO) { "Dropping session to ${location.endpointLabel}, its generation is gone" }
                        redundant = fresh
                        null
                    }

                    else -> {
                        val existing = generations[location.id]?.takeIf { !it.stale && it.binding == binding }
                        if (existing != null) {
                            // Raced another caller onto the same generation, keep theirs
                            redundant = fresh
                            existing.takeIf { lease(it) }
                        } else {
                            displaced = generations.remove(location.id)
                            generations[location.id] = fresh
                            fresh.takeIf { lease(it) }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            closeQuietly(fresh)
            throw error
        }
        redundant?.let { closeQuietly(it) }
        displaced?.let { markStale(it) }
        return leased
    }

    private suspend fun open(location: WebDavLocation, binding: Binding): Generation {
        val policy = location.pinnedCertificate
            ?.let { pin ->
                try {
                    CertificatePolicy.Pinned(pin.toServerCertificate())
                } catch (e: IllegalArgumentException) {
                    // Only the system's trust is left, an untrusted server then asks for a decision again.
                    log(TAG, WARN) { "Ignoring the unreadable pin of ${location.endpointLabel}: ${e.asLog()}" }
                    null
                }
            }
            ?: CertificatePolicy.System

        val credential = when (location.authType) {
            WebDavLocation.AuthType.BASIC -> credentialStore.resolve(location)
            WebDavLocation.AuthType.ANONYMOUS -> null
        }

        val session = try {
            connector.connect(
                location.toEndpoint(),
                credential?.toWebDavCredentials() ?: WebDavCredentials.Anonymous,
                policy,
            )
        } catch (error: Exception) {
            throw WebDavStatusMapper.mapConnect(error, location)
        } finally {
            credential?.wipe()
        }

        return Generation(location, binding, session)
    }

    private fun epochOf(locationId: Uuid): Long = closeEpoch + (evictionEpochs[locationId] ?: 0L)

    /** Drops the session of a location, e.g. after its credential changed. */
    suspend fun evict(locationId: Uuid) {
        val dropped = lock.withLock {
            evictionEpochs[locationId] = (evictionEpochs[locationId] ?: 0L) + 1
            generations.remove(locationId)
        }
        if (dropped != null) {
            log(TAG) { "Evicting the session of $locationId" }
            markStale(dropped)
        }
    }

    /**
     * Drops every session, e.g. when the last user of the gateway lets go. The pool itself stays
     * usable: the next operation reconnects instead of failing for the rest of the process.
     */
    suspend fun close() {
        val dropped = lock.withLock {
            closeEpoch++
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
        val first = synchronized(generation) {
            !generation.closed.also { generation.closed = true }
        }
        if (!first) return
        log(TAG, VERBOSE) { "Closing session for ${generation.location.endpointLabel}" }
        runCatching { generation.session.disconnect() }
    }

    companion object {
        val TAG = logTag("WebDAV", "ClientPool")

        private val IDLE_TIMEOUT = 60.seconds
        private val IDLE_CHECK_INTERVAL = 30.seconds
        private const val CONNECT_ATTEMPTS = 3
    }
}
