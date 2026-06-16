package eu.darken.butler.workspace.core

import eu.darken.butler.workspace.contracts.templates.TemplatesArguments

sealed interface WorkspaceAction {
    data class Create(
        val type: Workspace.Type = Workspace.Type.TEMPLATES,
        val arguments: Workspace.Arguments = TemplatesArguments.Default(),
        val replace: Workspace.Id? = null,
        val autoFocus: Boolean = false,
        val id: Workspace.Id? = null,
        /**
         * Skips both the free-tier total-count check AND the per-type singleton check.
         * Used by session restoration where the saved state is the source of truth.
         */
        val skipLimitCheck: Boolean = false,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(val newId: Workspace.Id) : Result
            data object LimitReached : Result

            /**
             * Returned when [Create.type] is a singleton ([Workspace.Type.isSingleton]) and an
             * instance already exists. Callers should focus the existing tab instead of
             * creating a duplicate.
             */
            data class AlreadyOpen(val existingId: Workspace.Id) : Result
        }
    }

    data class CreateBatch(
        val requests: List<Create>,
        val sourceWorkspaceId: Workspace.Id? = null,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            /**
             * Per-request results keyed by the [Create] request. Note: equal [Create] instances
             * (data-class equality on type + arguments + ids) collapse to a single entry. Repeated
             * singleton-type requests in one batch are deduped to a single creation; the extra
             * requests resolve to [CreationResult.AlreadyOpen] for the same instance. For
             * non-singleton types, duplicate Create requests within a batch are likely a programmer
             * error and are logged as a warning.
             */
            data class Success(
                val results: Map<Create, CreationResult>,
                /** Number of requested creates that were not created (e.g. skipped by the free-tier limit). */
                val skippedCount: Int,
            ) : Result

            data object Cancelled : Result

            data object AwaitingConfirmation : Result
        }

        sealed interface CreationResult {
            data class Success(val workspaceId: Workspace.Id) : CreationResult
            data class Failure(val exception: Exception) : CreationResult

            /**
             * The request targeted a singleton type whose instance already exists (or was created
             * earlier in the same batch). The caller should focus [existingId].
             */
            data class AlreadyOpen(val existingId: Workspace.Id) : CreationResult
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