package eu.darken.butler.workspace.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mutual exclusion between pausing a workspace and capturing its preview, per workspace id.
 *
 * Pausing releases a workspace instance, while
 * [eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewCaptureService] composes that same
 * instance's page host offscreen. Overlapping the two swaps the instance out from under a running
 * capture, which then renders blank. Both sides suspend for a while (pausing awaits
 * `createArguments()`, capturing awaits a composition), so checking the other side's state up front
 * is not enough - they have to hold each other off.
 *
 * The gate lives with the callers, not inside [WorkspaceRepo]: the repo is the domain layer and has
 * no business knowing that previews exist.
 *
 * Exclusion is per workspace id, so capturing tab A never delays pausing tab B.
 *
 * Lock ordering is lease -> [WorkspaceRepo] lock, never the reverse: callers take the lease first
 * and only then call into the repo. Nothing reached from inside a lease may take a lease again -
 * the mutexes are not reentrant, so a nested acquisition of the same id deadlocks.
 */
@Singleton
class WorkspacePauseGate @Inject constructor() {

    private class Lease {
        val mutex = Mutex()

        /** Holders plus waiters; at zero the lease leaves [leases] so ids cannot pile up. */
        var users = 0
    }

    private val guard = Mutex()
    private val leases = mutableMapOf<Workspace.Id, Lease>()

    /** Runs [block] with exclusive access to [id]; a concurrent caller for the same id waits. */
    suspend fun <R> withLease(id: Workspace.Id, block: suspend () -> R): R {
        val lease = guard.withLock { leases.getOrPut(id) { Lease() }.also { it.users++ } }
        try {
            if (lease.mutex.isLocked) log(TAG) { "Waiting for the lease of $id" }
            return lease.mutex.withLock { block() }
        } finally {
            // Cancellation must not strand the bookkeeping, or the map keeps dead ids forever
            withContext(NonCancellable) {
                guard.withLock {
                    lease.users--
                    if (lease.users == 0 && leases[id] === lease) leases.remove(id)
                }
            }
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "PauseGate")
    }
}
