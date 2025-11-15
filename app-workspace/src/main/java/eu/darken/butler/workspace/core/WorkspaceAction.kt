package eu.darken.butler.workspace.core

sealed interface WorkspaceAction {
    data class Create(
        val type: Workspace.Type = Workspace.Type.TEMPLATES,
        val arguments: Workspace.Arguments? = null,
        val replace: Workspace.Id? = null,
        val autoFocus: Boolean = false,
    ) : WorkspaceAction {
        data class Result(
            val newId: Workspace.Id,
        ) : WorkspaceAction.Result
    }

    data class CreateBatch(
        val requests: List<Create>,
        val sourceWorkspaceId: Workspace.Id? = null,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(
                val results: Map<Create, CreationResult>,
                val skippedCount: Int,
            ) : Result

            data object Cancelled : Result
        }

        sealed interface CreationResult {
            data class Success(val workspaceId: Workspace.Id) : CreationResult
            data class Failure(val exception: Exception) : CreationResult
        }
    }

    data class Close(
        val id: Workspace.Id,
        val requireConfirmation: Boolean = false,
    ) : WorkspaceAction {
        data object Result : WorkspaceAction.Result
    }

    data class Reorder(
        val workspaceIds: List<Workspace.Id>,
    ) : WorkspaceAction {
        data class Result(
            val success: Boolean,
        ) : WorkspaceAction.Result
    }

    data object CloseAll : WorkspaceAction {
        data object Result : WorkspaceAction.Result
    }

    interface Result
}