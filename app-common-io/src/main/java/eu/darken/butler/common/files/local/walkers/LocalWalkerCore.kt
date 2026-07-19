package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.isFile
import eu.darken.butler.common.files.extensions.isSymlink
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.coroutines.flow.FlowCollector
import java.util.LinkedList
import kotlin.coroutines.cancellation.CancellationException

/**
 * How a walker reads the filesystem. Implementations decide the access mechanism per call
 * (in-process ops, a fixed IPC mode, or per-subtree routing).
 */
internal interface WalkerStrategy {

    suspend fun lookupStart(start: LocalPath): LocalPathLookup

    suspend fun list(dir: LocalPathLookup): Listing

    suspend fun canonicalize(path: LocalPath): LocalPath

    suspend fun lookup(path: LocalPath): LocalPathLookup

    /** Called for each child directory the core is about to enqueue for traversal. */
    suspend fun onEnqueue(child: LocalPathLookup) {}

    sealed interface Listing {
        data class Children(val children: List<LocalPathLookup>) : Listing

        /** The strategy walked (or is streaming) the whole subtree itself; the core only emits. */
        data class Delegated(val subtree: kotlinx.coroutines.flow.Flow<LocalPathLookup>) : Listing
    }
}

/**
 * Shared traversal algorithm for all local walkers: LIFO queue (depth-first), [onFilter] as a
 * combined traversal-pruning + emission filter, [onError] deciding skip-vs-abort per directory,
 * and cycle-safe optional symlink following.
 *
 * Symlinks are always emitted. By default they are NOT followed. With [followSymlinks]=true they
 * are followed wherever they point (like `find -L`), with canonical-path cycle detection
 * guaranteeing termination. Destructive callers must keep this false.
 */
internal class LocalWalkerCore(
    private val strategy: WalkerStrategy,
    private val start: LocalPath,
    private val onFilter: suspend (LocalPathLookup) -> Boolean = { true },
    private val onError: suspend (LocalPathLookup, Exception) -> Boolean = { _, _ -> true },
    private val followSymlinks: Boolean = false,
    private val tag: String,
) {

    suspend fun walk(collector: FlowCollector<LocalPathLookup>) {
        val startLookUp = strategy.lookupStart(start)
        if (startLookUp.isFile) {
            collector.emit(startLookUp)
            return
        }

        val queue = LinkedList(listOf(startLookUp))
        // Canonical real-paths of directories descended into via a symlink; bounds traversal -> termination.
        val visitedCanonical: MutableSet<String>? = if (followSymlinks) HashSet<String>().also { set ->
            try {
                set.add(strategy.canonicalize(start).path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort seed: if the start can't be canonicalized, a symlink pointing back to start
                // just costs one extra traversal iteration before the cycle is detected (still bounded).
            }
        } else null

        while (!queue.isEmpty()) {
            val lookUp = queue.removeFirst()

            val listing = try {
                strategy.list(lookUp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to read $lookUp: $e" }
                if (onError(lookUp, e)) {
                    continue
                } else {
                    throw e
                }
            }

            when (listing) {
                is WalkerStrategy.Listing.Children -> listing.children
                    .filter {
                        val allowed = onFilter(it)
                        if (Bugs.isTrace) {
                            if (!allowed) log(tag, Logging.Priority.VERBOSE) { "Skipping (filter): $it" }
                        }
                        allowed
                    }
                    .forEach { child ->
                        if (shouldDescend(child, visitedCanonical)) {
                            if (Bugs.isTrace) log(tag, Logging.Priority.VERBOSE) { "Walking: $child" }
                            strategy.onEnqueue(child)
                            queue.addFirst(child)
                        }
                        collector.emit(child)
                    }
                is WalkerStrategy.Listing.Delegated -> listing.subtree.collect { collector.emit(it) }
            }
        }
    }

    private suspend fun shouldDescend(
        child: LocalPathLookup,
        visitedCanonical: MutableSet<String>?,
    ): Boolean {
        if (child.isSymlink) {
            if (!followSymlinks || visitedCanonical == null) return false
            val canonical = try {
                strategy.canonicalize(child.lookedUp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false // unresolvable target -> treat as leaf
            }
            val targetIsDir = try {
                strategy.lookup(canonical).isDirectory
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!targetIsDir) return false
            if (!visitedCanonical.add(canonical.path)) {
                log(tag, Logging.Priority.WARN) { "Symlink cycle, not following: $child -> ${canonical.path}" }
                return false
            }
            return true
        }
        return child.isDirectory
    }
}
