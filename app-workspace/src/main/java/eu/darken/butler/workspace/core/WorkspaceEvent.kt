package eu.darken.butler.workspace.core

import eu.darken.butler.common.files.APath

sealed interface WorkspaceEvent {
    /**
     * @param sourceWorkspaceId Workspace the create was invoked from, carried through as a pane
     * placement hint (see [WorkspaceAction.Create.sourceWorkspaceId]).
     */
    data class Created(
        val workspaceId: Workspace.Id,
        val replacedId: Workspace.Id? = null,
        val autoFocus: Boolean = false,
        val sourceWorkspaceId: Workspace.Id? = null,
    ) : WorkspaceEvent

    data class Closed(
        val workspaceId: Workspace.Id,
        val callerWorkspaceId: Workspace.Id? = null,
    ) : WorkspaceEvent

    data class Reordered(
        val workspaceIds: List<Workspace.Id>,
    ) : WorkspaceEvent

    /**
     * @param sourceWorkspaceId Workspace the selection was requested from, carried through as a pane
     * placement hint (see [WorkspaceAction.Create.sourceWorkspaceId]).
     */
    data class SelectionRequested(
        val workspaceId: Workspace.Id,
        val sourceWorkspaceId: Workspace.Id? = null,
    ) : WorkspaceEvent

    /**
     * A workspace's user-set name changed; [customTitle] is the normalized value, null when cleared.
     * Session saving subscribes to this for an immediate write instead of waiting for the debounce.
     */
    data class Renamed(
        val workspaceId: Workspace.Id,
        val customTitle: String?,
    ) : WorkspaceEvent

    data object AllClosed : WorkspaceEvent

    /**
     * Emitted when batch workspace creation completes.
     * Used to trigger feedback banner targeted to the workspace that initiated the request.
     *
     * @param successCount Number of successfully created workspaces
     * @param failureCount Number of failed workspace creations
     * @param skippedCount Number of items that were not created (e.g. skipped by the free-tier limit)
     * @param sourceWorkspaceId Workspace that initiated the request (null if from global action)
     */
    data class BatchCreationCompleted(
        val successCount: Int,
        val failureCount: Int,
        val skippedCount: Int,
        val sourceWorkspaceId: Workspace.Id?,
    ) : WorkspaceEvent

    /**
     * Base interface for workspace result events.
     * Used for workspaces that return results to caller workspaces (e.g., pickers, dialogs).
     */
    sealed interface ResultEvent : WorkspaceEvent {
        val workspaceId: Workspace.Id
        val callerWorkspaceId: Workspace.Id?
    }

    /**
     * Emitted when a picker workspace has selection confirmed
     *
     * @param workspaceId Workspace that generated the result (the picker)
     * @param callerWorkspaceId Workspace that expects the result (null if not specified)
     * @param selectedPaths Selected file/folder paths
     * @param filename Filename provided by user in SaveAs mode (null for other modes)
     */
    data class PickerResult(
        override val workspaceId: Workspace.Id,
        override val callerWorkspaceId: Workspace.Id?,
        val selectedPaths: List<APath<*>>,
        val filename: String? = null,
    ) : ResultEvent

    /**
     * Emitted by a Saver that was asked to report where it wrote
     * ([eu.darken.butler.workspace.contracts.saver.SaverArguments.Default.reportSavedPaths]).
     *
     * Informational, unlike [PickerResult]: the Saver keeps running afterwards, with its
     * "Open saved file" and "Save again" actions intact. A caller acting on it has to check
     * [workspaceId] against the Saver it launched - it is not the only Saver that can be open.
     *
     * @param savedPaths Files that were actually written, in report order.
     */
    data class SaveResult(
        override val workspaceId: Workspace.Id,
        override val callerWorkspaceId: Workspace.Id?,
        val savedPaths: List<APath<*>>,
    ) : ResultEvent

    /**
     * Emitted when a result-returning workspace is cancelled without providing a result
     *
     * @param workspaceId Workspace that was cancelled
     * @param callerWorkspaceId Workspace that was expecting a result (null if not specified)
     */
    data class ResultCancelled(
        override val workspaceId: Workspace.Id,
        override val callerWorkspaceId: Workspace.Id?,
    ) : ResultEvent
}