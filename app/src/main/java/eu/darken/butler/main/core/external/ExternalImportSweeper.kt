package eu.darken.butler.main.core.external

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import eu.darken.butler.workspace.ui.session.WorkspaceSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Deletes cache imports ([ExternalContentImporter]) once nothing can reach them any more.
 *
 * An import used to live until a 7-day age sweep happened to run, so closing the tab that opened a
 * shared file left its copy behind indefinitely. Age alone cannot decide this: a viewer restored
 * from a week-old session still needs its file, while a copy whose tab was closed a minute ago is
 * already garbage.
 *
 * Reachability is derived, not counted. A refcount drifts as soon as one increment or decrement is
 * missed - a crash between the two, a workspace replaced in place, an import handed to a second tab.
 * Instead every sweep asks the current holders what they reference right now and deletes what nobody
 * named, so a wrong answer corrects itself on the next pass instead of leaking or over-deleting
 * permanently.
 *
 * Holders are asked through the arguments they would be restored from ([Workspace.createArguments]),
 * serialized with the same factories the session save uses. That covers every argument carrying a
 * path without this class knowing any of them: a viewer's file, an explorer's CURRENT location (its
 * arguments track where the tab navigated to, not where it started), a saver's source URIs. Creates
 * parked behind the free-tier limit dialog, active operations and the clipboard are asked separately,
 * because all three hold a path while no workspace does.
 *
 * The one thing a workspace type has to get right for this to work: whatever it currently needs on
 * disk must appear in [Workspace.createArguments]. A type that deliberately reports less than it
 * holds would have its import collected out from under it.
 */
@Singleton
class ExternalImportSweeper(
    private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val importer: ExternalContentImporter,
    private val workspaceRepo: WorkspaceRepo,
    private val sessionManager: WorkspaceSessionManager,
    private val operationsManager: OperationsManager,
    private val clipboardRepo: ClipboardRepo,
    private val closedStash: ClosedWorkspaceStash,
    private val factoryMap: Map<Workspace.Type, @JvmSuppressWildcards WorkspaceFactory<*>>,
    private val json: Json,
    private val clock: Clock,
) {

    @Inject constructor(
        @AppScope appScope: CoroutineScope,
        dispatcherProvider: DispatcherProvider,
        importer: ExternalContentImporter,
        workspaceRepo: WorkspaceRepo,
        sessionManager: WorkspaceSessionManager,
        operationsManager: OperationsManager,
        clipboardRepo: ClipboardRepo,
        closedStash: ClosedWorkspaceStash,
        factoryMap: Map<Workspace.Type, @JvmSuppressWildcards WorkspaceFactory<*>>,
        json: Json,
    ) : this(
        appScope,
        dispatcherProvider,
        importer,
        workspaceRepo,
        sessionManager,
        operationsManager,
        clipboardRepo,
        closedStash,
        factoryMap,
        json,
        Clock.System,
    )

    private val sweepMutex = Mutex()

    /** Collapses a burst of closes - closing a tab also closes its children - into one sweep. */
    private val requests = Channel<Boolean>(Channel.CONFLATED)

    fun start() {
        log(TAG, INFO) { "start()" }

        appScope.launch {
            // A close can only happen after the session was restored, so this needs no gate.
            workspaceRepo.events
                .filterIsInstance<WorkspaceEvent.Closed>()
                .collect { requests.trySend(false) }
        }

        appScope.launch {
            // Startup pass for imports orphaned by a crash or a kill: nothing emits a Closed event
            // for those. Gated on the restore, because until it finishes the workspaces holding the
            // imports do not exist yet and every import would look unreachable.
            //
            // Restored or Disabled only. A FAILED restore is the one state where the saved rows
            // outlive the workspaces on purpose - the session is kept for a retry - so sweeping then
            // would delete the imports of exactly the tabs that are waiting to come back.
            val settled = sessionManager.state.first { it !is WorkspaceSessionManager.State.Restoring }
            when (settled) {
                is WorkspaceSessionManager.State.Restored,
                is WorkspaceSessionManager.State.Disabled -> requests.trySend(false)

                else -> log(TAG, WARN) { "Not sweeping at startup, session did not restore: $settled" }
            }
        }

        appScope.launch {
            var consecutiveRetries = 0
            requests.consumeEach { isRetry ->
                delay(SWEEP_DEBOUNCE)
                val result = try {
                    runSweep()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Sweep failed: ${e.asLog()}" }
                    null
                }

                consecutiveRetries = if (isRetry) consecutiveRetries + 1 else 0

                // Something was passed over for being too young or still copying. Both clear on
                // their own, and nothing else would come back for them - the next trigger is another
                // tab close, which may never happen - so the sweep re-arms itself. Bounded, so a copy
                // that hangs forever cannot turn this into an endless poll.
                if (result != null && result.skipped > 0 && consecutiveRetries < MAX_CONSECUTIVE_RETRIES) {
                    appScope.launch {
                        delay(MIN_AGE)
                        requests.trySend(true)
                    }
                }
            }
        }
    }

    /**
     * Deletes every import directory nothing references, returning how many went.
     *
     * Imports being written ([ExternalContentImporter.inFlight]) and imports younger than [MIN_AGE]
     * are left alone: both are cases where no holder can name the import yet, so "unreferenced" does
     * not mean "garbage".
     */
    suspend fun sweep(): Int = runSweep().deleted

    /** [deleted] went; [skipped] were passed over for a reason that resolves on its own. */
    internal data class SweepResult(val deleted: Int, val skipped: Int)

    private suspend fun runSweep(): SweepResult = sweepMutex.withLock {
        val candidates = withContext(dispatcherProvider.IO) {
            importer.baseDir.listFiles()?.toList() ?: emptyList()
        }
        if (candidates.isEmpty()) return@withLock SweepResult(0, 0)

        val referenced = collectReferences() ?: run {
            log(TAG, WARN) { "Could not determine what is referenced, skipping sweep" }
            return@withLock SweepResult(0, 0)
        }
        log(TAG) { "Sweeping ${candidates.size} import(s) against ${referenced.size} reference(s)" }

        val inFlight = importer.inFlight
        val cutOff = clock.now().toEpochMilliseconds() - MIN_AGE.inWholeMilliseconds
        var deleted = 0
        var skipped = 0

        for (dir in candidates) {
            // Being written right now. Its directory is already old enough to look sweepable while a
            // large copy is still running, and no workspace can reference it until the copy returns.
            if (inFlight.contains(dir.name)) {
                log(TAG, VERBOSE) { "Import still being written: $dir" }
                skipped++
                continue
            }

            // The directory name is a random UUID, so a reference containing it is that import and
            // nothing else. Matching on the whole serialized argument blob keeps this independent of
            // how any given workspace spells its paths.
            if (referenced.any { it.contains(dir.name) }) continue

            // Newest stamp in the tree, not the directory's own: a directory is stamped when the
            // copy CREATES the file, so a slow copy that just finished would otherwise read as old
            // enough to sweep in the moment between the copy returning and its workspace existing.
            val lastTouched = withContext(dispatcherProvider.IO) {
                (dir.walkTopDown().map { it.lastModified() }.maxOrNull() ?: dir.lastModified())
            }
            if (lastTouched > cutOff) {
                log(TAG, VERBOSE) { "Too young to sweep, may not be attached yet: $dir" }
                skipped++
                continue
            }

            val gone = withContext(dispatcherProvider.IO) {
                try {
                    dir.deleteRecursively() || !dir.exists()
                } catch (e: Exception) {
                    log(TAG, WARN) { "Failed to delete unreferenced import $dir: ${e.asLog()}" }
                    false
                }
            }
            if (gone) {
                log(TAG, INFO) { "Deleted unreferenced import $dir" }
                deleted++
            }
        }

        SweepResult(deleted = deleted, skipped = skipped)
    }

    /**
     * Every string a live holder could name an import in. Null if any holder could not be asked:
     * deleting on a partial answer would take a file still in use, so the sweep is skipped instead
     * and retried on the next close.
     */
    private suspend fun collectReferences(): Set<String>? {
        val references = mutableSetOf<String>()

        // Asked FIRST, and before the live workspaces: a tab the user can still bring back holds
        // whatever it pointed at just as much as an open one does, and the window between the close
        // and the undo is exactly when the import looks unreachable.
        closedStash.peekStashedArguments().forEach { arguments ->
            @Suppress("UNCHECKED_CAST")
            val factory = factoryMap[arguments.type] as? WorkspaceFactory<Workspace.Arguments> ?: run {
                log(TAG, WARN) { "No factory for stashed ${arguments.type}, cannot read its references" }
                return null
            }
            val serialized = try {
                factory.serialize(json, arguments).toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "Could not read stashed ${arguments.type} arguments: ${e.asLog()}" }
                return null
            }
            references.add(serialized)
        }

        // peekAll(), never state: the state flow is an asynchronous share whose replay can still be
        // mid-restore and list fewer workspaces than exist, and a workspace missing from that list
        // reads as "nobody holds this import". peekAll() is the same authoritative list the session
        // save confirms its own deletions against.
        //
        // Live AND paused: a paused tab is a stand-in that still holds its arguments and is restored
        // on focus, so its import has to survive.
        workspaceRepo.peekAll().forEach { workspace ->
            val info = workspace.info.value
            @Suppress("UNCHECKED_CAST")
            val factory = factoryMap[info.type] as? WorkspaceFactory<Workspace.Arguments> ?: run {
                log(TAG, WARN) { "No factory for ${info.type}, cannot read its references" }
                return null
            }
            val serialized = try {
                factory.serialize(json, workspace.createArguments()).toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "Could not read arguments of ${info.id}: ${e.asLog()}" }
                return null
            }
            references.add(serialized)
        }

        // A create the free-tier limit blocked is a holder too. It owns an import that was already
        // written but has no workspace yet, and its dialog can sit open for as long as the user
        // leaves it, so nothing time-based can stand in for asking.
        workspaceRepo.peekPendingCreateArguments().forEach { arguments ->
            @Suppress("UNCHECKED_CAST")
            val factory = factoryMap[arguments.type] as? WorkspaceFactory<Workspace.Arguments> ?: run {
                log(TAG, WARN) { "No factory for parked ${arguments.type}, cannot read its references" }
                return null
            }
            val serialized = try {
                factory.serialize(json, arguments).toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "Could not read parked ${arguments.type} arguments: ${e.asLog()}" }
                return null
            }
            references.add(serialized)
        }

        operationsManager.operations.first().forEach { managed ->
            managed.operation.metadata.pathPlan?.allPaths?.forEach { references.add(it.path) }
        }

        clipboardRepo.state.first().entries.forEach { clip ->
            if (clip is ClipboardClip.Paths) clip.paths.forEach { references.add(it.path) }
        }

        return references
    }

    companion object {
        private val TAG = logTag("Main", "ExternalOpen", "Sweeper")

        /** Long enough for the close burst of a tab plus its children to settle. */
        private val SWEEP_DEBOUNCE: Duration = 5.seconds

        /**
         * Covers the gap between an import finishing and its workspace existing. Short on purpose:
         * the handoff is immediate, a still-running copy is excluded by
         * [ExternalContentImporter.inFlight] rather than by age, and a long window would leave the
         * ordinary case - open a shared file, look at it, close the tab - uncollected until the next
         * close or app start.
         */
        internal val MIN_AGE: Duration = 30.seconds

        /** Caps the self-re-arm, so an import that never stops looking busy cannot poll forever. */
        private const val MAX_CONSECUTIVE_RETRIES = 5
    }
}
