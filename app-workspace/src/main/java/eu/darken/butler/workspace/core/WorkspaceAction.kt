package eu.darken.butler.workspace.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import kotlin.time.Instant

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
        /**
         * Skips the content-path dedup check for this create alone, so it commits even when another
         * workspace of the same type already publishes the path.
         *
         * For the narrow case of a workspace binding ITSELF to a path it just produced (the viewer
         * rebinding to a saved copy): the alternative is refusing the create and focusing a foreign
         * tab, which abandons the workspace the user was working in. Ordinary creates must keep
         * deduping, so this stays opt-in per call site.
         */
        val skipContentDedup: Boolean = false,
        /**
         * Workspace this create was invoked from, if any. Purely a placement hint: the UI prefers a
         * pane adjacent to it and never evicts the pane it occupies. Null (the default) means
         * "no origin" - global entry points, the tab manager and session restore - and keeps
         * today's first-empty-pane behaviour.
         */
        val sourceWorkspaceId: Workspace.Id? = null,
        /**
         * When this workspace came into existence. Null (the default) stamps the moment the repo
         * commits it; session restore passes the persisted value so "oldest tab" keeps meaning the
         * tab the user opened first instead of degenerating to restore order after every app start.
         */
        val createdAt: Instant? = null,
        /**
         * Opts this create into the limit dialog's "close the oldest tab" action: when the free-tier
         * limit blocks it, the request is retained and replayed once the user frees a slot.
         *
         * Off by default because a repo-level replay cannot reproduce every caller's completion
         * semantics — a caller that assigns the new workspace to a specific pane, or deliberately
         * creates in the background, would lose that. Only callers whose follow-up is exactly
         * "select the new (or already open) workspace" may set it, see `createAndFocus`.
         */
        val allowLimitRecovery: Boolean = false,
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
        /** Persisted creation time of the restored workspace, see [Create.createdAt]. */
        val createdAt: Instant? = null,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(val newId: Workspace.Id) : Result
            data class Failed(val error: Throwable) : Result
        }
    }

    /**
     * Replaces paused stand-ins with their real instances in place: same [Workspace.Id], same list
     * position, so focus, pane selections and tab identity survive. Idempotent — an unknown or
     * already resumed id resolves to [Result.NoOp].
     *
     * Acts on the whole ownership UNIT [id] belongs to, exactly like [Pause]: the id is resolved up
     * to its ownership root and every paused member below it comes back too. A member is only
     * instantiated after its owner resumed successfully — a modal over a paused owner has nothing to
     * bind to — so a failing member skips its own descendants
     * ([MemberOutcome.SkippedAncestorFailed]) while independent branches continue.
     */
    data class Resume(
        val id: Workspace.Id,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(
                val newId: Workspace.Id,
                /** Per-member outcome, keyed by [Workspace.Id] (unique by construction). */
                val outcomes: Map<Workspace.Id, MemberOutcome> = emptyMap(),
            ) : Result

            /** The id is unknown, nothing in its unit is paused, or its ownership is broken. */
            data object NoOp : Result

            /**
             * The requested id did not come back: its own instantiation failed, or an ancestor's did.
             * Failing stand-ins are kept and report the error.
             */
            data class Failed(
                val error: Throwable,
                val outcomes: Map<Workspace.Id, MemberOutcome> = emptyMap(),
            ) : Result
        }

        sealed interface MemberOutcome {
            data object Resumed : MemberOutcome

            /** Was not paused to begin with; nothing to do. */
            data object AlreadyLive : MemberOutcome

            data class Failed(val error: Throwable) : MemberOutcome

            /** Never attempted: [ancestorId] failed, so this one would hang over a paused owner. */
            data class SkippedAncestorFailed(
                val ancestorId: Workspace.Id,
                val error: Throwable,
            ) : MemberOutcome
        }
    }

    /**
     * Releases live workspace instances to free memory and battery, replacing each in place with a
     * paused stand-in that holds the arguments captured from its CURRENT state. Same [Workspace.Id],
     * same list position, same focus and pane assignments — only the instances go away, so no
     * [WorkspaceEvent] is emitted. [Resume] brings them back.
     *
     * Acts on the ownership UNIT [id] belongs to, never on a single workspace: the id is resolved up
     * to its ownership root (following [Workspace.Info.callerWorkspaceId]) and the root plus all of
     * its descendants are paused as one, all-or-nothing. A tab and its modal children live and die
     * together on screen, so pausing one without the others would either leave a live modal over a
     * released owner or keep a whole tab awake for a forgotten overlay.
     *
     * Refused whenever pausing would lose something, for ANY member of the unit: a child that does
     * not opt into being paused with its owner (see [Workspace.ArgumentsWithCaller.pausableAsChild],
     * which pickers never do), a held content-path claim, running operations, unsaved changes, a
     * workspace that isn't [Workspace.Info.isPausable], and anything not yet in
     * [Workspace.LifecycleState.Ready].
     */
    data class Pause(
        val id: Workspace.Id,
    ) : WorkspaceAction {
        sealed interface Result : WorkspaceAction.Result {
            data class Success(
                /** The ownership root the request resolved to; not necessarily [Pause.id]. */
                val id: Workspace.Id,
                /** Every member of the paused unit, root first. Authoritative topology snapshot. */
                val pausedIds: List<Workspace.Id> = listOf(id),
            ) : Result

            /** The id is unknown, or its whole unit is already paused; nothing to do. */
            data object NoOp : Result

            data class Refused(val reason: Reason) : Result

            /** Capturing the arguments failed before any state change; the unit is still live. */
            data class Failed(val error: Throwable) : Result
        }

        enum class Reason {
            /** A member of the unit does not opt into being paused with its owner. */
            HAS_CHILDREN,
            BUSY,
            UNSAVED_CHANGES,
            NOT_PAUSABLE,
            NOT_READY,
            CLAIM_HELD,

            /** The caller chain cannot be resolved: a cycle, or a caller id that no longer exists. */
            BROKEN_OWNERSHIP,
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

    /**
     * Closes [id] and, recursively, everything it owns.
     *
     * @param sourceWorkspaceId the workspace the close was invoked from, when that is not [id]
     * itself - closing a whole unit from one of its overlays is the case that needs it. A close
     * confirmation is hosted in its target workspace's pane layer, so it must be anchored to the
     * workspace the user is actually looking at; anchoring it to [id] would compose the dialog
     * underneath the overlay that asked for the close, leaving it invisible and the close pending.
     * Null means the invoking workspace is [id].
     */
    data class Close(
        val id: Workspace.Id,
        val requireConfirmation: Boolean = false,
        val sourceWorkspaceId: Workspace.Id? = null,
    ) : WorkspaceAction {
        data object Result : WorkspaceAction.Result
    }

    /**
     * Reorders the open workspaces. [ownerIds] is a UNIT order - one id per ownership unit, which is
     * what every surface that offers reordering lists - and the repo expands it against the current
     * topology, keeping each unit's members adjacent to their owner and in their existing relative
     * order. An order that does not cover every unit is refused.
     */
    data class Reorder(
        val ownerIds: List<Workspace.Id>,
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