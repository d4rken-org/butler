package eu.darken.butler.workspace.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Emits a result event and automatically closes the workspace.
 * Convenience function for the common pattern of returning a result and closing.
 *
 * @param event The result event to emit
 */
suspend fun WorkspaceRemote.returnResult(event: WorkspaceEvent.ResultEvent) {
    emitEvent(event)
    execute(WorkspaceAction.Close(event.workspaceId))
}

/**
 * Emits a cancellation event and closes the workspace.
 * Used when a result-returning workspace is dismissed without providing a result.
 *
 * @param workspaceId The workspace being cancelled
 * @param callerWorkspaceId The workspace that was expecting a result
 */
suspend fun WorkspaceRemote.cancelResult(
    workspaceId: Workspace.Id,
    callerWorkspaceId: Workspace.Id?,
) {
    emitEvent(WorkspaceEvent.ResultCancelled(workspaceId, callerWorkspaceId))
    execute(WorkspaceAction.Close(workspaceId))
}

/**
 * Filters and handles result events for a specific caller workspace.
 * Automatically closes the result workspace after handling.
 *
 * @param T The specific result event type to handle
 * @param callerWorkspaceId The workspace ID expecting results
 * @param onResult Callback invoked when a matching result is received
 * @return Flow that processes matching result events (terminal - emits nothing downstream)
 */
inline fun <reified T : WorkspaceEvent.ResultEvent> Flow<WorkspaceEvent>.handleResult(
    callerWorkspaceId: Workspace.Id,
    crossinline onResult: suspend (T) -> Unit,
): Flow<T> = this
    .filter { it is T && it.callerWorkspaceId == callerWorkspaceId }
    .map { it as T }
    .onEach { result ->
        onResult(result)
    }
    .filter { false } // Terminal operator - no downstream emissions
    .map { it } // Ensures correct type
