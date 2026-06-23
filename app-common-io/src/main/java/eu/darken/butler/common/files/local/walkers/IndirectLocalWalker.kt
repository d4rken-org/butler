package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.isFile
import eu.darken.butler.common.files.extensions.isSymlink
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import java.util.LinkedList
import kotlin.coroutines.cancellation.CancellationException

// Symlinks are always emitted. By default they are NOT followed (cycle-safe). With followSymlinks=true
// they are followed wherever they point (like `find -L`), with canonical-path cycle detection guaranteeing
// termination. Resolution is mode-aware (canonicalize/lookup route through ROOT/ADB when needed).
// Destructive callers (delete/cleanup) must keep this false (mirrors `rm -r` not following links).
class IndirectLocalWalker(
    private val gateway: LocalGateway,
    private val mode: LocalGateway.Mode = LocalGateway.Mode.AUTO,
    private val start: LocalPath,
    private val lookupOptions: LookupOptions,
    private val onFilter: suspend (LocalPathLookup) -> Boolean = { true },
    private val onError: suspend (LocalPathLookup, Exception) -> Boolean = { _, _ -> true },
    private val followSymlinks: Boolean = false,
) : AbstractFlow<LocalPathLookup>() {
    private val tag = "$TAG#${hashCode()}"

    override suspend fun collectSafely(collector: FlowCollector<LocalPathLookup>) {
        val startLookUp = gateway.lookup(start, lookupOptions, mode)

        if (startLookUp.isFile) {
            collector.emit(startLookUp)
            return
        }

        val queue = LinkedList(listOf(startLookUp))
        // Canonical real-paths of directories descended into via a symlink; bounds traversal -> termination.
        val visitedCanonical: MutableSet<String>? = if (followSymlinks) HashSet<String>().also { set ->
            try {
                set.add(gateway.canonicalize(start, mode).path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort seed: if the start can't be canonicalized, a symlink pointing back to start
                // just costs one extra traversal iteration before the cycle is detected (still bounded).
            }
        } else null

        while (!queue.isEmpty()) {
            val lookUp = queue.removeFirst()

            val newBatch = try {
                gateway.lookupFiles(lookUp.lookedUp, lookupOptions, mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.ERROR) { "Failed to read $lookUp: $e" }
                if (onError(lookUp, e)) {
                    emptyList()
                } else {
                    throw e
                }
            }

            newBatch
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
                        queue.addFirst(child)
                    }
                    collector.emit(child)
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
                gateway.canonicalize(child.lookedUp, mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false // unresolvable target -> treat as leaf
            }
            val targetIsDir = try {
                gateway.lookup(canonical, lookupOptions, mode).isDirectory
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

    companion object {
        private val TAG = logTag("Gateway", "Local", "Walker", "Indirect")
    }
}
