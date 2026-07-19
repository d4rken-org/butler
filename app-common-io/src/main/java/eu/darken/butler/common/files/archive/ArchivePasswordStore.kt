package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory (never persisted) archive passwords, keyed by full container path identity.
 * Cleared on process death; replaced/evicted passwords are wiped.
 */
@Singleton
class ArchivePasswordStore @Inject constructor() {

    private val passwords = ConcurrentHashMap<APath<*>, CharArray>()

    // Synchronized so a reader's copyOf() can't race a concurrent set/evict zeroing the same array.
    @Synchronized
    fun get(container: APath<*>): CharArray? = passwords[container]?.copyOf()

    @Synchronized
    fun set(container: APath<*>, password: CharArray) {
        passwords.put(container, password.copyOf())?.fill(Char(0))
    }

    @Synchronized
    fun evict(container: APath<*>) {
        passwords.remove(container)?.fill(Char(0))
    }
}
