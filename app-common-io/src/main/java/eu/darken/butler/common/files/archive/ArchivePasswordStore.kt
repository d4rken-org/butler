package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory (never persisted) archive passwords, keyed by full container path identity.
 * Cleared on process death; replaced/evicted passwords are wiped.
 *
 * Every [set] and [evict] bumps the container's generation, so a cache or a pending eviction can
 * tell whether the password it was based on is still the one stored.
 */
@Singleton
class ArchivePasswordStore @Inject constructor() {

    private val passwords = ConcurrentHashMap<APath<*>, CharArray>()
    private val generations = ConcurrentHashMap<APath<*>, Int>()

    /** A copy of the password (wiped by the caller) and the generation it belongs to, read together. */
    class Snapshot(val password: CharArray?, val generation: Int)

    // Synchronized so a reader's copyOf() can't race a concurrent set/evict zeroing the same array.
    @Synchronized
    fun get(container: APath<*>): CharArray? = passwords[container]?.copyOf()

    @Synchronized
    fun snapshot(container: APath<*>): Snapshot =
        Snapshot(passwords[container]?.copyOf(), generations[container] ?: 0)

    @Synchronized
    fun generation(container: APath<*>): Int = generations[container] ?: 0

    @Synchronized
    fun set(container: APath<*>, password: CharArray) {
        passwords.put(container, password.copyOf())?.fill(Char(0))
        generations.merge(container, 1, Int::plus)
    }

    @Synchronized
    fun evict(container: APath<*>) {
        passwords.remove(container)?.fill(Char(0))
        generations.merge(container, 1, Int::plus)
    }

    /** Evicts only if nothing replaced the password since [generation] was read. */
    @Synchronized
    fun evictIfGeneration(container: APath<*>, generation: Int): Boolean {
        if (generation(container) != generation) return false
        evict(container)
        return true
    }
}
