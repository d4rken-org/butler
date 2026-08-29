package eu.darken.butler.common.error

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * The incidents frozen for errors that are currently on screen, keyed by the throwable itself.
 *
 * The site that publishes an error and the site that shares it are usually far apart (a card here,
 * a Share action there). Both name the same throwable instance, so that instance is the key: no
 * incident id has to be threaded through state classes, actions and exception types.
 */
@Singleton
class ErrorIncidentStore @Inject constructor(
    private val incidentFactory: ErrorIncidentFactory,
) {

    /**
     * Identity, never [Throwable.equals]: exception types that are Kotlin data classes compare by
     * value, so two separate failures with the same message would share one incident.
     */
    private class IdentityKey(val error: Throwable) {
        override fun hashCode(): Int = System.identityHashCode(error)
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.error === error
    }

    // Guards both maps below, neither of which is read from a suspending section.
    private val lock = Any()

    private val incidents = LinkedHashMap<IdentityKey, ErrorIncident>()

    /**
     * The freezes currently running, one per identity. Two callers racing on the same throwable
     * still mint once, while two unrelated failures freeze side by side: a freeze reads settings
     * and writes a log trail to disk, and the second failure's ring buffer keeps evicting for as
     * long as it would spend waiting.
     */
    private val inFlight = HashMap<IdentityKey, CompletableDeferred<ErrorIncident>>()

    /**
     * The incidents a consent dialog is currently holding, by id. Nothing calls [forget], so the
     * map fills up with dismissed errors too, and at the cap the eldest entry's log trail is
     * deleted - which, without this, could be the one the packager is about to read.
     */
    private val pinned = HashSet<String>()

    private val spoolCleanupLock = Mutex()
    private var spoolsCleared = false

    /**
     * Freezes [error] unless it is already held, in which case the incident it was frozen with is
     * returned unchanged - same id, same timestamp, same spooled log trail.
     *
     * Called where the failure reaches the user, so the stamp names the failure itself: either the
     * exact [occurredAt] the site recorded, or the moment it published the error.
     */
    suspend fun remember(
        error: Throwable,
        context: Map<String, String?> = emptyMap(),
        occurredAt: Instant? = null,
    ): ErrorIncident {
        clearStaleSpoolsOnce()
        return mint(
            error = error,
            context = context,
            occurredAt = occurredAt,
            occurredAtIsApproximate = false,
        )
    }

    fun get(error: Throwable): ErrorIncident? = synchronized(lock) { incidents[IdentityKey(error)] }

    /**
     * The share side's fallback: an error nobody remembered is still worth a report, it just costs
     * the log trail from around the failure. The `incident.frozenAtShare` marker rides along into
     * the report, so a site that stopped publishing the instance it remembered shows up in the
     * field rather than only in a test.
     */
    suspend fun getOrFreeze(
        error: Throwable,
        context: Map<String, String?> = emptyMap(),
        occurredAt: Instant? = null,
    ): ErrorIncident {
        get(error)?.let { return it }
        log(TAG, WARN) { "No incident held for ${error.javaClass.name}, freezing at share time" }
        clearStaleSpoolsOnce()
        return mint(
            error = error,
            context = context + ("incident.frozenAtShare" to "true"),
            occurredAt = occurredAt,
            occurredAtIsApproximate = true,
        )
    }

    /** Points [wrapper] at the incident already held for [original], for a site that publishes a wrapper. */
    suspend fun alias(wrapper: Throwable, original: Throwable) {
        val incident = get(original)
        if (incident == null) {
            log(TAG, WARN) { "alias(): Nothing remembered for ${original.javaClass.name}" }
            return
        }
        store(wrapper, incident)
    }

    suspend fun forget(error: Throwable) {
        val dropped = synchronized(lock) { incidents.remove(IdentityKey(error)) } ?: return
        log(TAG) { "forget(${dropped.incidentId})" }
        deleteSpoolOf(dropped)
    }

    /** Holds [incident] against eviction for as long as a pending share needs it. */
    fun pin(incident: ErrorIncident) {
        synchronized(lock) { pinned.add(incident.incidentId) }
    }

    fun unpin(incident: ErrorIncident) {
        synchronized(lock) { pinned.remove(incident.incidentId) }
    }

    private suspend fun mint(
        error: Throwable,
        context: Map<String, String?>,
        occurredAt: Instant?,
        occurredAtIsApproximate: Boolean,
    ): ErrorIncident {
        val key = IdentityKey(error)
        var owned = false
        val pending = synchronized(lock) {
            incidents[key]?.let { return it }
            inFlight[key] ?: CompletableDeferred<ErrorIncident>().also {
                inFlight[key] = it
                owned = true
            }
        }
        if (!owned) return pending.await()

        val incident = try {
            incidentFactory.freeze(
                error = error,
                siteContext = context,
                occurredAt = occurredAt,
                occurredAtIsApproximate = occurredAtIsApproximate,
            )
        } catch (t: Throwable) {
            // A failed freeze must not wedge the next caller naming this throwable.
            synchronized(lock) { inFlight.remove(key) }
            pending.completeExceptionally(t)
            throw t
        }
        synchronized(lock) { inFlight.remove(key) }
        store(error, incident)
        pending.complete(incident)
        return incident
    }

    /**
     * The spool outlives the process, the map naming its owners does not: whatever is on disk when
     * this store is first used belongs to a previous session and nothing can reach it again.
     */
    private suspend fun clearStaleSpoolsOnce() = spoolCleanupLock.withLock {
        if (spoolsCleared) return@withLock
        try {
            incidentFactory.clearStaleSpools()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log(TAG, WARN) { "clearStaleSpools() failed: ${t.asLog()}" }
        }
        spoolsCleared = true
    }

    private fun store(error: Throwable, incident: ErrorIncident) {
        val evicted = synchronized(lock) {
            incidents[IdentityKey(error)] = incident
            if (incidents.size <= MAX_ENTRIES) return@synchronized null
            val eldest = incidents.entries.firstOrNull { it.value.incidentId !in pinned }
                ?: return@synchronized null
            incidents.remove(eldest.key)
        } ?: return
        log(TAG) { "Evicted incident ${evicted.incidentId}" }
        deleteSpoolOf(evicted)
    }

    /** The store owns the spool files, so a dropped incident takes its log trail with it. */
    private fun deleteSpoolOf(incident: ErrorIncident) {
        val logFile = incident.logFile ?: return
        val stillNeeded = synchronized(lock) {
            // An aliased throwable keeps the incident alive under a second key.
            incident.incidentId in pinned || incidents.values.any { it === incident }
        }
        if (stillNeeded) return
        runCatching { logFile.delete() }
    }

    companion object {
        private val TAG = logTag("Error", "Incident", "Store")
        const val MAX_ENTRIES = 32
    }
}
