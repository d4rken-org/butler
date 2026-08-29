package eu.darken.butler.common.error

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
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

    // Held across the freeze, which suspends: two callers racing on one throwable must mint once.
    private val mintLock = Mutex()

    // Guards the map itself, which [get] reads without suspending.
    private val incidents = LinkedHashMap<IdentityKey, ErrorIncident>()

    /**
     * Freezes [error] unless it is already held, in which case the incident it was frozen with is
     * returned unchanged - same id, same timestamp, same spooled log trail.
     */
    suspend fun remember(
        error: Throwable,
        context: Map<String, String?> = emptyMap(),
        occurredAt: Instant? = null,
    ): ErrorIncident = mintLock.withLock {
        get(error)?.let { return@withLock it }
        val incident = incidentFactory.freeze(error = error, siteContext = context, occurredAt = occurredAt)
        store(error, incident)
        incident
    }

    fun get(error: Throwable): ErrorIncident? = synchronized(incidents) { incidents[IdentityKey(error)] }

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
        return remember(
            error = error,
            context = context + ("incident.frozenAtShare" to "true"),
            occurredAt = occurredAt,
        )
    }

    /** Points [wrapper] at the incident already held for [original], for a site that publishes a wrapper. */
    suspend fun alias(wrapper: Throwable, original: Throwable) = mintLock.withLock {
        val incident = get(original)
        if (incident == null) {
            log(TAG, WARN) { "alias(): Nothing remembered for ${original.javaClass.name}" }
            return@withLock
        }
        store(wrapper, incident)
    }

    suspend fun forget(error: Throwable) = mintLock.withLock {
        val dropped = synchronized(incidents) { incidents.remove(IdentityKey(error)) } ?: return@withLock
        log(TAG) { "forget(${dropped.incidentId})" }
        deleteSpoolOf(dropped)
    }

    private fun store(error: Throwable, incident: ErrorIncident) {
        val evicted = synchronized(incidents) {
            incidents[IdentityKey(error)] = incident
            if (incidents.size <= MAX_ENTRIES) return@synchronized null
            val eldest = incidents.keys.first()
            incidents.remove(eldest)
        } ?: return
        log(TAG) { "Evicted incident ${evicted.incidentId}" }
        deleteSpoolOf(evicted)
    }

    /** The store owns the spool files, so a dropped incident takes its log trail with it. */
    private fun deleteSpoolOf(incident: ErrorIncident) {
        val logFile = incident.logFile ?: return
        // An aliased throwable keeps the incident alive under a second key.
        val stillReferenced = synchronized(incidents) { incidents.values.any { it === incident } }
        if (stillReferenced) return
        runCatching { logFile.delete() }
    }

    companion object {
        private val TAG = logTag("Error", "Incident", "Store")
        const val MAX_ENTRIES = 32
    }
}
