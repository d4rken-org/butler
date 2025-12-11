package eu.darken.butler.workspace.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.explorer.core.picker.PickerConfig
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

/**
 * Launches a picker workspace for file/folder selection.
 * Convenience function for the common pattern of creating a sub-workspace for path selection.
 *
 * @param callerWorkspaceId The workspace ID requesting the picker
 * @param startPath Optional starting path for the picker (null = home)
 * @param selection The picker mode (FileSingle, FileMulti, DirectorySingle, DirectoryMulti, MixedMulti)
 * @param requireWritable When true, only writable paths can be confirmed
 * @return The Create action result with the new workspace ID
 */
suspend fun WorkspaceRemote.launchPicker(
    callerWorkspaceId: Workspace.Id,
    startPath: APath<*>? = null,
    selection: PickerConfig.Selection,
    requireWritable: Boolean = false,
): WorkspaceAction.Create.Result {
    // Implementation detail: Uses Explorer workspace for picker functionality
    return execute(
        WorkspaceAction.Create(
            type = Workspace.Type.EXPLORER,
            arguments = ExplorerArguments.Picker(
                startPath = startPath,
                selection = selection,
                requireWritable = requireWritable,
                callerWorkspaceId = callerWorkspaceId,
            )
        )
    ) as WorkspaceAction.Create.Result
}

/**
 * Creates a new workspace and requests UI focus/selection for it.
 * Convenience function for the common pattern of creating a workspace and switching to it.
 *
 * @param type The type of workspace to create
 * @param arguments Optional workspace-specific arguments
 * @return The Create action result with the new workspace ID
 */
suspend fun WorkspaceRemote.createAndFocus(
    type: Workspace.Type,
    arguments: Workspace.Arguments,
): WorkspaceAction.Create.Result {
    val result = execute(
        WorkspaceAction.Create(
            type = type,
            arguments = arguments
        )
    ) as WorkspaceAction.Create.Result

    emitEvent(WorkspaceEvent.SelectionRequested(result.newId))

    return result
}
