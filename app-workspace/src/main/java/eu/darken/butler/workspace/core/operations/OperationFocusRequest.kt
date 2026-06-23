package eu.darken.butler.workspace.core.operations

import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Race-free hand-off channel used when the user taps an operation notification that needs
 * attention (a [Operation.State.Waiting] conflict). The notification tap focuses the owning
 * workspace via [eu.darken.butler.workspace.core.WorkspaceEvent.SelectionRequested]; this holder
 * additionally carries the [Operation.Id] so the focused workspace's ViewModel can surface the
 * matching conflict sheet.
 *
 * Backed by a [StateFlow] (latest-value replay) rather than a transient event so the request is
 * still observable if the target ViewModel subscribes after the tap was dispatched.
 */
@Singleton
class OperationFocusRequest @Inject constructor() {

    data class Request(
        val workspaceId: Workspace.Id,
        val operationId: Operation.Id,
    )

    private val _requests = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = _requests.asStateFlow()

    fun request(workspaceId: Workspace.Id, operationId: Operation.Id) {
        _requests.value = Request(workspaceId, operationId)
    }

    /** Clears [request] only if it is still the current value (avoids clobbering a newer request). */
    fun consume(request: Request) {
        _requests.compareAndSet(request, null)
    }

    /**
     * Drops any pending request targeting [workspaceId]. Called when that workspace's ViewModel is
     * cleared so an unfulfilled request (e.g. the conflict resolved before the app opened) can't
     * linger for the rest of the process.
     */
    fun clearForWorkspace(workspaceId: Workspace.Id) {
        val current = _requests.value ?: return
        if (current.workspaceId == workspaceId) _requests.compareAndSet(current, null)
    }
}
