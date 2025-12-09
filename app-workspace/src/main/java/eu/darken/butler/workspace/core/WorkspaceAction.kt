package eu.darken.butler.workspace.core

import eu.darken.butler.templates.core.arguments.TemplatesArguments

sealed interface WorkspaceAction {
    data class Create(
        val type: Workspace.Type = Workspace.Type.TEMPLATES,
        val arguments: Workspace.Arguments = TemplatesArguments.Default(),
        val replace: Workspace.Id? = null,
        val autoFocus: Boolean = false,
        val id: Workspace.Id? = null,
        val skipLimitCheck: Boolean = false,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(val newId: Workspace.Id) : Result
            data object LimitReached : Result
        }
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