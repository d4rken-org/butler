package eu.darken.butler.workspace.core

import eu.darken.butler.common.files.APath
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
             * Returned when a matching instance already exists: [Create.type] is a singleton
             * ([Workspace.Type.isSingleton]), or a same-type workspace already publishes the
             * requested [Workspace.ArgumentsWithContentPath.contentPath]. Callers should focus
             * the existing tab instead of creating a duplicate.
             */
            data class AlreadyOpen(val existingId: Workspace.Id) : Result
        }
    }

    /**
     * Registers a lightweight paused stand-in for a saved workspace: the entry occupies its slot in
     * the workspace list (tabs, reorder, close, session saving, quota accounting) while holding only
     * its [arguments], performing no I/O until [Resume] swaps in the real instance.
     *
     * Restore-only semantics are baked in: appended in restore order, never auto-focused, never a
     * replace, and — like a restoring [Create] with `skipLimitCheck` — not limit checked.
     */
    data class RegisterPaused(
        val id: Workspace.Id,
        val type: Workspace.Type,
        val arguments: Workspace.Arguments,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(val newId: Workspace.Id) : Result
            data class Failed(val error: Throwable) : Result
        }
    }

    /**
     * Replaces a paused stand-in with its real instance in place: same [Workspace.Id], same list
     * position, so focus, pane selections and tab identity survive. Idempotent — an unknown or
     * already resumed id resolves to [Result.NoOp].
     */
    data class Resume(
        val id: Workspace.Id,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(val newId: Workspace.Id) : Result

            /** The id is unknown or its workspace is not paused; nothing to do. */
            data object NoOp : Result

            /** Instantiation failed; the stand-in is kept and reports the error. */
            data class Failed(val error: Throwable) : Result
        }
    }

    /**
     * Releases a live workspace's instance to free memory and battery, replacing it in place with a
     * paused stand-in that holds the arguments captured from its CURRENT state. Same [Workspace.Id],
     * same list position, same focus and pane assignments — only the instance goes away, so no
     * [WorkspaceEvent] is emitted. [Resume] brings it back.
     *
     * Refused whenever pausing would lose something: sub-workspaces and workspaces with children,
     * a held content-path claim, running operations, unsaved changes, a workspace that isn't
     * [Workspace.Info.isPausable], and anything not yet in [Workspace.LifecycleState.Ready].
     */
    data class Pause(
        val id: Workspace.Id,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(val id: Workspace.Id) : Result

            /** The id is unknown, or its workspace is already paused; nothing to do. */
            data object NoOp : Result

            data class Refused(val reason: Reason) : Result

            /** Capturing the arguments failed before any state change; the workspace is still live. */
            data class Failed(val error: Throwable) : Result
        }

        enum class Reason {
            SUB_WORKSPACE,
            HAS_CHILDREN,
            BUSY,
            UNSAVED_CHANGES,
            NOT_PAUSABLE,
            NOT_READY,
            CLAIM_HELD,
            ;
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
             * The request targeted a singleton type or an already-published content path whose
             * instance already exists (or was created earlier in the same batch). The caller
             * should focus [existingId].
             */
            data class AlreadyOpen(val existingId: Workspace.Id) : CreationResult
        }
    }

    /**
     * Atomically resolves [contentPath] for [claimantId]: if a same-type workspace (other than
     * the claimant) already publishes or has claimed the path, returns [Result.AlreadyOpen];
     * otherwise reserves the path for the claimant so concurrent Creates/claims dedup to it
     * until [ReleaseContentPath] or the claimant closes. Used by in-tab open flows (e.g. the
     * editor's Open picker) that change content without going through [Create].
     */
    data class ClaimContentPath(
        val type: Workspace.Type,
        val contentPath: APath<*>,
        val claimantId: Workspace.Id,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            /** The path is reserved for the claimant; callers must release it when done. */
            data object Granted : Result

            /** Another workspace already holds or claimed the path; focus it instead. */
            data class AlreadyOpen(val existingId: Workspace.Id) : Result
        }
    }

    /** Releases a claim made via [ClaimContentPath]; no-op when not owned by [claimantId]. */
    data class ReleaseContentPath(
        val claimantId: Workspace.Id,
        val contentPath: APath<*>,
    ) : WorkspaceAction {
        data object Result : WorkspaceAction.Result
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

    /**
     * Sets or clears the user-set name of a workspace ([Workspace.Info.customTitle]). A null, blank
     * or control-character-only [customTitle] clears it and restores the automatic naming.
     */
    data class Rename(
        val id: Workspace.Id,
        val customTitle: String?,
    ) : WorkspaceAction {
        data class Result(val success: Boolean) : WorkspaceAction.Result

        companion object {
            /**
             * Longest custom title that survives normalization. Declared here so the UI cap (which
             * only exists to give immediate feedback while typing) can never drift past the limit
             * the repo actually enforces, which would silently drop characters on confirm.
             * The repo's normalization stays authoritative.
             */
            const val MAX_CUSTOM_TITLE_LENGTH = 128
        }
    }

    data object CloseAll : WorkspaceAction {
        data object Result : WorkspaceAction.Result
    }

    interface Result
}