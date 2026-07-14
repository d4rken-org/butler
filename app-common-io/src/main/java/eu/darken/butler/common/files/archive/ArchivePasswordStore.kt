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

    fun get(container: APath<*>): CharArray? = passwords[container]?.copyOf()

    fun set(container: APath<*>, password: CharArray) {
        passwords.put(container, password.copyOf())?.fill(Char(0))
    }

    fun evict(container: APath<*>) {
        passwords.remove(container)?.fill(Char(0))
    }
}
