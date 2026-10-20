package eu.darken.butler.workspace.core.hosted

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.contracts.picker.HostedPickerRequests
import eu.darken.butler.workspace.contracts.picker.HostedPickerRequests.Outcome
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns every [HostedPickerRequests] request from registration until its picker is gone.
 *
 * A request moves `REGISTERED -> LAUNCHING -> OPEN -> FINISHED`. Its picker is created with a
 * preallocated id the moment its destination first attaches, so a request abandoned while the create
 * is still in flight is closed once the create lands instead of leaking a workspace nobody shows.
 *
 * Only the picker created here, answering to this request's host id, can finish a request: a picker
 * the hosted one opens itself reports to its own caller and only changes what the destination renders.
 */
@Singleton
class HostedPickerCoordinator @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val workspaceRemote: WorkspaceRemote,
) : HostedPickerRequests {

    enum class Phase { REGISTERED, LAUNCHING, OPEN, FINISHED }

    /** What the destination of a request needs: the picker it renders and whether it is done. */
    data class HostState(val pickerId: Workspace.Id, val phase: Phase)

    private data class Entry(
        val request: HostedPickerRequests.Request,
        val hostId: Workspace.Id,
        val pickerId: Workspace.Id,
        val outcome: CompletableDeferred<Outcome>,
        val phase: Phase = Phase.REGISTERED,
        /** Someone gave up on the picker: close it as soon as it exists. */
        val closeWanted: Boolean = false,
        /** The registering caller no longer waits for [outcome], so FINISHED may drop the entry. */
        val collected: Boolean = false,
    )

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val subscribed = CompletableDeferred<Unit>()
    private val closers = ConcurrentHashMap<String, Job>()

    init {
        workspaceRemote.events
            .onStart { subscribed.complete(Unit) }
            .onEach { onEvent(it) }
            .launchIn(appScope)
    }

    override fun register(request: HostedPickerRequests.Request): HostedPickerRequests.Ticket {
        val hostId = Workspace.Id()
        val ticket = HostedPickerRequests.Ticket(hostId.longTag)
        val entry = Entry(
            request = request,
            hostId = hostId,
            pickerId = Workspace.Id(),
            outcome = CompletableDeferred(),
        )
        entries.update { it + (ticket.token to entry) }
        log(TAG, INFO) { "register(${hostId.shortTag}): $request" }
        return ticket
    }

    override suspend fun awaitOutcome(ticket: HostedPickerRequests.Ticket): Outcome {
        val entry = entries.value[ticket.token] ?: return Outcome.Cancelled
        try {
            val outcome = entry.outcome.await()
            // Returns once the picker itself is gone, not just once it answered
            entries.first { all -> all[ticket.token]?.let { it.phase == Phase.FINISHED } ?: true }
            return outcome
        } finally {
            // Also on cancellation: nobody is left to deliver a result to, so the picker has to go
            withContext(NonCancellable) {
                if (!entry.outcome.isCompleted) close(ticket.token)
                modify(ticket.token) { it.copy(collected = true) }
            }
        }
    }

    /**
     * The destination of [token] attached: launches its picker on first attach and reports the
     * request's state. Emits null once the request is gone, including for a token this process never
     * registered (a destination restored after process death).
     */
    fun attach(token: String): Flow<HostState?> {
        var launch = false
        modify(token) { entry ->
            // update() may run this more than once, so the flag is decided by the attempt that sticks
            launch = false
            if (entry.phase != Phase.REGISTERED) return@modify entry
            launch = true
            entry.copy(phase = Phase.LAUNCHING)
        }
        if (launch) appScope.launch { launchPicker(token) }

        return entries
            .map { all -> all[token]?.let { HostState(it.pickerId, it.phase) } }
            .distinctUntilChanged()
    }

    /**
     * Closes the picker of [token], now or as soon as its create lands. Its outcome becomes
     * [Outcome.Cancelled] unless it already picked something. Idempotent.
     */
    fun close(token: String) {
        var closeNow = false
        var finishedUnlaunched = false
        modify(token) { entry ->
            closeNow = false
            finishedUnlaunched = false
            when (entry.phase) {
                Phase.REGISTERED -> {
                    finishedUnlaunched = true
                    entry.copy(phase = Phase.FINISHED, closeWanted = true)
                }
                Phase.LAUNCHING -> entry.copy(closeWanted = true)
                Phase.OPEN -> {
                    closeNow = true
                    entry.copy(closeWanted = true)
                }
                Phase.FINISHED -> entry
            }
        }
        if (finishedUnlaunched) {
            entries.value[token]?.outcome?.complete(Outcome.Cancelled)
            dropIfDone(token)
        }
        if (closeNow) ensureClosing(token)
    }

    private suspend fun launchPicker(token: String) {
        val entry = entries.value[token] ?: return
        subscribed.await()
        val arguments = ExplorerArguments.Picker(
            startPath = entry.request.startPath,
            selection = PickerConfig.Selection.DirectorySingle,
            callerWorkspaceId = entry.hostId,
            modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
            isHostedByNavigation = true,
        )
        val result = try {
            workspaceRemote.execute(
                WorkspaceAction.Create(type = Workspace.Type.EXPLORER, arguments = arguments, id = entry.pickerId)
            )
        } catch (e: Exception) {
            log(TAG, WARN) { "Creating picker for ${entry.hostId.shortTag} failed: ${e.asLog()}" }
            null
        }

        if (result !is WorkspaceAction.Create.Result.Success) {
            log(TAG, WARN) { "Picker for ${entry.hostId.shortTag} did not come up: $result" }
            finish(token, Outcome.Cancelled)
            return
        }

        // OPEN before the existence check: a removal before this point is caught by the check, one
        // after it by the event (AllClosed only finishes OPEN requests).
        var closeWanted = false
        modify(token) { current ->
            closeWanted = false
            if (current.phase != Phase.LAUNCHING) return@modify current
            closeWanted = current.closeWanted
            current.copy(phase = Phase.OPEN)
        }
        if (workspaceRemote.peekInfos().none { it.id == entry.pickerId }) {
            log(TAG, WARN) { "Picker for ${entry.hostId.shortTag} was removed while coming up" }
            finish(token, Outcome.Cancelled)
            return
        }
        log(TAG) { "Picker ${entry.pickerId.shortTag} open for ${entry.hostId.shortTag}" }
        if (closeWanted) ensureClosing(token)
    }

    /**
     * Keeps closing the picker of [token] until it is gone. Outlives any destination: nothing else
     * closes a hosted picker nobody shows.
     */
    private fun ensureClosing(token: String, initialDelay: Duration = Duration.ZERO) {
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(initialDelay)
                closeUntilGone(token)
            } finally {
                closers.remove(token)
            }
        }
        if (closers.putIfAbsent(token, job) == null) job.start() else job.cancel()
    }

    private suspend fun closeUntilGone(token: String) {
        while (true) {
            val entry = entries.value[token] ?: return
            if (entry.phase == Phase.FINISHED) return
            if (workspaceRemote.peekInfos().any { it.id == entry.pickerId }) {
                workspaceRemote.execute(WorkspaceAction.Close(entry.pickerId))
            }
            if (workspaceRemote.peekInfos().none { it.id == entry.pickerId }) {
                finish(token, Outcome.Cancelled)
                return
            }
            log(TAG, WARN) { "Picker ${entry.pickerId.shortTag} refused to close, retrying in $CLOSE_RETRY" }
            delay(CLOSE_RETRY)
        }
    }

    private fun onEvent(event: WorkspaceEvent) {
        when (event) {
            // Schedules a close check after CLOSE_RETRY in case the picker is still there
            is WorkspaceEvent.PickerResult -> forPicker(event.workspaceId, event.callerWorkspaceId) { token ->
                entries.value[token]?.outcome?.complete(Outcome.Picked(event.selectedPaths))
                ensureClosing(token, initialDelay = CLOSE_RETRY)
            }
            is WorkspaceEvent.ResultCancelled -> forPicker(event.workspaceId, event.callerWorkspaceId) { token ->
                entries.value[token]?.outcome?.complete(Outcome.Cancelled)
                ensureClosing(token, initialDelay = CLOSE_RETRY)
            }
            is WorkspaceEvent.Closed -> forPicker(event.workspaceId, callerId = null) { token ->
                finish(token, Outcome.Cancelled)
            }
            is WorkspaceEvent.AllClosed -> entries.value
                .filterValues { it.phase == Phase.OPEN }
                .keys
                .forEach { finish(it, Outcome.Cancelled) }
            else -> Unit
        }
    }

    /** Runs [block] for the request whose own picker is [pickerId]; [callerId] must match its host id when given. */
    private inline fun forPicker(pickerId: Workspace.Id, callerId: Workspace.Id?, block: (String) -> Unit) {
        val (token, entry) = entries.value.entries.firstOrNull { it.value.pickerId == pickerId } ?: return
        if (callerId != null && callerId != entry.hostId) return
        block(token)
    }

    /** First outcome wins: a picker emits its result before it closes, and the close must not undo it. */
    private fun finish(token: String, fallback: Outcome) {
        val entry = entries.value[token] ?: return
        entry.outcome.complete(fallback)
        modify(token) { it.copy(phase = Phase.FINISHED) }
        log(TAG, INFO) { "Request ${entry.hostId.shortTag} finished" }
        dropIfDone(token)
    }

    private fun dropIfDone(token: String) {
        entries.update { all ->
            val entry = all[token] ?: return@update all
            if (entry.phase == Phase.FINISHED && entry.collected) all - token else all
        }
    }

    private inline fun modify(token: String, crossinline transform: (Entry) -> Entry) {
        entries.update { all ->
            val entry = all[token] ?: return@update all
            all + (token to transform(entry))
        }
        dropIfDone(token)
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds abstract fun requests(coordinator: HostedPickerCoordinator): HostedPickerRequests
    }

    companion object {
        private val TAG = logTag("Workspace", "HostedPicker")
        internal val CLOSE_RETRY = 2.seconds
    }
}
