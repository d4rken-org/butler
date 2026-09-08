package eu.darken.butler.explorer.core.sizes

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.extensions.isAncestorOf
import eu.darken.butler.common.files.extensions.isAncestorOfOrSelf
import eu.darken.butler.explorer.core.sorting.rules.TabSortRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * The scans one Explorer tab has in memory. Results are tab-local and die with it.
 */
class DirectorySizeStore {

    data class Snapshot(
        /** Keyed by the scan root's [APath.path]. */
        val scans: Map<String, DirectoryScan> = emptyMap(),
        /** Roots with a scan in flight. */
        val running: Map<String, APath<*>> = emptyMap(),
        /** Running roots that saw an overlapping change; their results are dropped at publish. */
        val stale: Set<String> = emptySet(),
        /** Roots whose result already flipped the tab to size sort, keyed like [scans]. */
        val sortSwitches: Map<String, SortRestore> = emptyMap(),
    ) {
        /** The deepest scan whose root is [directory] or an ancestor of it. */
        fun scanFor(directory: APath<*>): DirectoryScan? = scans.values
            .filter { it.root.isAncestorOfOrSelf(directory) }
            .maxByOrNull { it.root.path.length }

        fun isRunning(directory: APath<*>): Boolean = directory.path in running
    }

    /** What the tab sorted by before a scan's result switched it to size. */
    data class SortRestore(
        /** Null when the folder had no tab-local rule at all, i.e. the switch has to be undone by removal. */
        val previousRule: TabSortRule?,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Reserves [root]; false when a scan of it is already running (the caller must not start another). */
    fun markRunning(root: APath<*>): Boolean {
        val key = root.path
        val previous = _snapshot.getAndUpdate { current ->
            if (key in current.running) current else current.copy(running = current.running + (key to root))
        }
        return key !in previous.running
    }

    /** Remembers what to sort by again if [root]'s sizes are discarded; the first record for a root wins. */
    fun recordSortSwitch(root: APath<*>, previousRule: TabSortRule?) {
        val key = root.path
        _snapshot.update { current ->
            if (key in current.sortSwitches) current
            else current.copy(sortSwitches = current.sortSwitches + (key to SortRestore(previousRule)))
        }
    }

    /** Forgets [root]'s scan and returns how its sort switch is to be undone, if there was one. */
    fun discard(root: APath<*>): SortRestore? {
        val key = root.path
        val previous = _snapshot.getAndUpdate { current ->
            current.copy(scans = current.scans - key, sortSwitches = current.sortSwitches - key)
        }
        return previous.sortSwitches[key]
    }

    fun markFinished(root: APath<*>) {
        val key = root.path
        _snapshot.update { current ->
            current.copy(running = current.running - key, stale = current.stale - key)
        }
    }

    /**
     * Stores [scan], unless it was marked stale while it ran - publishing it then would overwrite
     * the store with a pre-change total stamped with a post-change time.
     *
     * @return true when [scan] was stored, false when it was dropped as stale.
     */
    fun publish(scan: DirectoryScan): Boolean {
        val key = scan.root.path
        var stored = false
        _snapshot.update { current ->
            if (key in current.stale) {
                stored = false
                return@update current
            }
            // A fresh parent covers everything below it.
            val retained = current.scans.filterValues { !scan.root.isAncestorOf(it.root) }
            stored = true
            current.copy(
                scans = retained + (key to scan),
                sortSwitches = current.sortSwitches.filterKeys { it == key || it in retained },
            )
        }
        return stored
    }

    /**
     * Drops every scan that overlaps one of [paths] in either direction: a scan of `/a/b/c` is just
     * as dead when `/a/b` is moved away as when something below `/a/b/c` changes.
     */
    fun invalidate(paths: Collection<APath<*>>) {
        if (paths.isEmpty()) return
        _snapshot.update { current ->
            val retained = current.scans.filterValues { scan -> paths.none { it.overlaps(scan.root) } }
            val nowStale = current.running.filterValues { root -> paths.any { it.overlaps(root) } }.keys
            current.copy(
                scans = retained,
                stale = current.stale + nowStale,
                sortSwitches = current.sortSwitches.filterKeys { it in retained },
            )
        }
    }
}

private fun APath<*>.overlaps(root: APath<*>): Boolean =
    root.isAncestorOfOrSelf(this) || this.isAncestorOf(root)
