package eu.darken.butler.workspace.core

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.parcel.UuidParceler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlin.uuid.Uuid

interface Workspace<ArgT : Workspace.Arguments> {
    val id: Id
    val type: Type

    /**
     * Workspace metadata as observable state.
     *
     * [StateFlow] so that consumers (e.g. WorkspaceRepo lifecycle decisions) can read
     * [StateFlow.value] synchronously. The initial value must carry the correct static fields
     * ([Info.callerWorkspaceId], [Info.modalPresentation]) — either seed a [kotlinx.coroutines.flow.MutableStateFlow]
     * accordingly or use `initialInfo()` + `stateInWorkspace()` from WorkspaceInfoExtensions.
     */
    val info: StateFlow<Info>

    /**
     * The arguments used to create this workspace.
     * For session restoration, workspaces may return current state as Arguments
     * instead of original creation arguments.
     */
    suspend fun createArguments(): ArgT

    suspend fun release() {

    }

    enum class Type(
        val isSingleton: Boolean = false,
        /**
         * When true, instances of this type never count toward the free-tier workspace limit and can
         * always be created even when the user is already at the limit. Reserved for system/utility
         * workspaces (developer tools, bug reports) that the user must always be able to reach.
         */
        val isQuotaExempt: Boolean = false,
    ) {
        TEMPLATES,
        EXPLORER,
        SEARCHER,
        EDITOR,
        APPS,
        APP_DETAILS,
        SAVER,
        DEVELOPER(isSingleton = true, isQuotaExempt = true),
        HISTORY,
        BUG_REPORT(isSingleton = true, isQuotaExempt = true),

        // Appended last on purpose: the ordinal is part of no persisted format, but keeping the
        // existing order stable avoids churn in anything that reads entries positionally.
        VIEWER,
        ;
    }

    /**
     * Lifecycle state shared by all workspaces for global Init/Error/Ready handling.
     * Each workspace derives this from its internal state in the [info] flow.
     *
     * Named `LifecycleState` (not `State`) to avoid collision with workspace-specific
     * State sealed interfaces like `ExplorerWorkspace.State`.
     */
    sealed interface LifecycleState {
        data object Initializing : LifecycleState
        data class Error(val error: Throwable) : LifecycleState
        data object Ready : LifecycleState

        /**
         * The workspace exists as a lightweight stand-in that holds its arguments but has no live
         * instance: either it was never instantiated (session restore) or its instance was released
         * to save memory and battery ([WorkspaceAction.Pause]). It performs no I/O until it is
         * resumed, either by gaining focus or via the placeholder's resume button.
         *
         * [error] carries the failure of the last resume attempt, if any. A failed resume
         * stays [Paused] and never becomes [Error]: [Error] composes the workspace's typed page
         * host, which would cast the stand-in to a concrete workspace type.
         */
        data class Paused(val error: Throwable? = null) : LifecycleState
    }

    @Parcelize
    @TypeParceler<Uuid, UuidParceler>
    data class Id(
        val id: Uuid = Uuid.random(),
    ) : Parcelable {
        val shortTag: String
            get() = id.toString().take(4)
        val longTag: String
            get() = id.toString()

        override fun toString(): String = "Workspace.Id($shortTag)"
    }

    interface Arguments : Parcelable {
        val type: Type

        /**
         * Whether a workspace built from these arguments may be written to the session database.
         *
         * False for arguments that only mean something inside the process that produced them: a
         * `content://` URI another app granted us reads fine now and is dead after the task is
         * removed, so persisting it would restore a tab that can never load. Such a workspace is
         * still an ordinary tab, it simply does not survive a restart.
         *
         * @see Info.isPersistable
         */
        val isPersistable: Boolean get() = true
    }

    /**
     * Optional interface for workspaces that support advanced session state restoration.
     *
     * Session restoration happens in two phases:
     * 1. Basic: Workspace created from Arguments (always, automatic via @Serializable)
     * 2. Advanced: CustomState restored (optional, if workspace implements SessionAware)
     *
     * Arguments should contain "content location" (path, file, package),
     * while CustomState contains "UI/interaction state" (scroll, filters, view mode).
     */
    interface SessionAware {
        /**
         * Extract current custom state for session persistence.
         * Return null if workspace has no meaningful custom state to preserve.
         */
        suspend fun extractCustomState(): CustomState?

        /**
         * Restore custom state after workspace creation.
         * Called after workspace is created from Arguments during session restoration.
         */
        suspend fun restoreCustomState(state: CustomState)

        /**
         * Marker interface for workspace-specific custom state.
         * Must be both Serializable (for persistence) and Parcelable (for Android).
         * Implementing classes should be annotated with @Serializable.
         */
        interface CustomState : Parcelable
    }

    /**
     * Defines how a modal workspace should be presented to the user.
     */
    enum class ModalPresentationMode {
        /**
         * Always render as full-screen modal overlay, regardless of device layout.
         * Used for picker workspaces that require focused user interaction across entire screen.
         *
         * Rendering: a Dialog covering the whole window, at any pane count.
         */
        FULL_SCREEN,

        /**
         * Render as overlay local to parent's pane.
         * Used for detail/informational workspaces opened from a specific parent workspace.
         *
         * Rendering: a Box stacked on the parent's pane, at any pane count. On a single-pane
         * layout that pane is the pager page the parent's tab occupies.
         *
         * Example: Clicking an app in Apps workspace opens AppDetails as overlay within Apps pane.
         */
        PANE_LOCAL,
    }

    /**
     * Arguments for workspaces created by another workspace (parent-child relationship).
     *
     * This interface establishes a parent-child relationship where:
     * - The child workspace (sub-workspace) tracks its parent via [callerWorkspaceId]
     * - When the parent closes, all children automatically close
     * - Child workspaces are typically rendered as modals (presentation controlled by [modalPresentation])
     *
     * Example: AppsWorkspace creates AppDetailsWorkspace with [callerWorkspaceId] = apps workspace ID.
     * The app details renders as an overlay stacked inside the Apps pane, whatever the layout.
     *
     * @see callerWorkspaceId
     * @see modalPresentation
     */
    interface ArgumentsWithCaller : Arguments {
        /**
         * ID of the workspace that created this workspace.
         * Null if this workspace was not created by another workspace.
         *
         * This property enables:
         * - Automatic lifecycle management (parent-child cleanup)
         * - UI layer derivation of presentation mode (modal vs tab)
         */
        val callerWorkspaceId: Id?

        /**
         * Preferred modal presentation mode. Defaults to PANE_LOCAL for detail views.
         * Caller can override this when creating the workspace.
         */
        val modalPresentation: ModalPresentationMode
            get() = ModalPresentationMode.PANE_LOCAL

        /**
         * True when this workspace may be released together with the workspace that owns it
         * ([WorkspaceAction.Pause] acts on a whole ownership unit).
         *
         * Fail-closed by design: opting in means everything the child stands for survives a
         * round-trip through [Arguments], so it can be rebuilt exactly as the user left it once the
         * unit resumes. [ArgumentsForResult] must never opt in — the result collector lives in the
         * caller's ViewModel and cannot survive its workspace being released.
         */
        val pausableAsChild: Boolean
            get() = false
    }

    /**
     * Arguments for workspaces that are created to produce a result for another workspace
     * (e.g., picker workspaces, selection dialogs).
     *
     * This interface extends [ArgumentsWithCaller] for workspaces that return results to their
     * parent via [eu.darken.butler.workspace.core.WorkspaceEvent].
     *
     * Presentation is not implied by returning a result: like any sub-workspace these are
     * [ModalPresentationMode.PANE_LOCAL] by default, so a picker launched from a pane stays inside
     * that pane and leaves the rest of the screen usable. A caller that genuinely needs the whole
     * screen — because the choice is about the app rather than about one pane's content — passes
     * [ModalPresentationMode.FULL_SCREEN] explicitly. The pane count does not change either: a
     * pane-local picker stacks inside its owning tab on a phone too.
     *
     * Example: SearcherWorkspace creates ExplorerPickerWorkspace to select a directory.
     * The Explorer picker renders inside the Searcher's pane, returns the selected path, then closes.
     *
     * @see ArgumentsWithCaller
     */
    interface ArgumentsForResult : ArgumentsWithCaller

    /**
     * Arguments that bind the workspace to one content path. A [WorkspaceAction.Create] carrying
     * these dedups against open same-type workspaces publishing the same [Info.contentPath],
     * returning [WorkspaceAction.Create.Result.AlreadyOpen] instead of creating a duplicate.
     */
    interface ArgumentsWithContentPath : Arguments {
        /** The content path this workspace would represent; null disables dedup (e.g. scratch tabs). */
        val contentPath: APath<*>?
    }

    data class Info(
        val id: Id,
        val type: Type,
        val title: CaString,
        val subtitle: CaString? = null,
        /**
         * Lifecycle state of this workspace.
         * Used by UI layer for global Init/Error/Ready handling at the WorkspaceMapper level.
         *
         * Each workspace derives this from its internal state in the info flow.
         * UI consumers (tabs, previews) can use this for visual indicators.
         */
        val lifecycleState: LifecycleState = LifecycleState.Initializing,
        val operationCount: Int = 0,
        val attentionCount: Int = 0,
        /**
         * True when this workspace holds unsaved in-memory changes that would be lost on close.
         * Domain signal: the close path uses it to require confirmation, the UI to warn the user.
         * Workspaces without a dirty concept leave this false.
         */
        val hasUnsavedChanges: Boolean = false,
        /**
         * True when releasing this instance right now would not lose in-flight work or state that
         * [createArguments] cannot reproduce — i.e. the workspace may be paused
         * ([WorkspaceAction.Pause]). Workspaces whose arguments fully describe them leave this true.
         *
         * A fast reactive signal, not an atomic one: WorkspaceRepo re-checks it after capturing the
         * arguments, right before the live instance is swapped for the stand-in.
         */
        val isPausable: Boolean = true,
        /**
         * ID of the workspace that created this workspace, if this is a sub-workspace.
         * Null for normal workspaces.
         *
         * This is a domain property representing workspace ownership/relationship.
         * The UI layer uses this to derive presentation (via [isSubWorkspace] and [modalPresentation]).
         *
         * @see ArgumentsWithCaller
         * @see isSubWorkspace
         */
        val callerWorkspaceId: Id? = null,
        /**
         * Preferred modal presentation mode for this workspace.
         * Only relevant when [isSubWorkspace] is true.
         *
         * This property is passed from Arguments and determines how the modal should render:
         * - FULL_SCREEN: Always full-screen Dialog overlay covering all panes
         * - PANE_LOCAL: Box overlay stacked within the parent's pane, at any pane count
         *
         * @see ModalPresentationMode
         */
        val modalPresentation: ModalPresentationMode = ModalPresentationMode.PANE_LOCAL,
        /**
         * True when this workspace may be paused together with the workspace that owns it, projected
         * from [ArgumentsWithCaller.pausableAsChild]. Only relevant while [isSubWorkspace] is true.
         *
         * A cheap pre-filter for the UI and auto-pause; WorkspaceRepo re-checks the same property on
         * the arguments it actually captured before releasing anything.
         */
        val pausableAsChild: Boolean = false,
        /**
         * Content path this workspace currently holds (e.g. the editor's open file), making it
         * eligible for duplicate-open focusing: a Create/claim for the same path resolves to a
         * workspace publishing it instead of opening a duplicate. NOT an exclusivity guarantee -
         * Save-As convergence or restored sessions can legitimately produce several workspaces on
         * one path; matches resolve to the first in workspace order. Comparison is plain [APath]
         * equality (no canonicalization of symlinks, mount aliases, case, or URI encoding).
         */
        val contentPath: APath<*>? = null,
        /**
         * User-set name that replaces the automatic [title] in the tab chrome. Null when the user
         * has not named this workspace, which is also what an empty/blank rename resets it to.
         *
         * Owned and overlaid by the repo, not by the workspace implementation: [title] keeps
         * meaning "automatic title" and stays live while a custom name is set.
         */
        val customTitle: String? = null,
        /**
         * Whether this workspace may be written to the session database, projected from
         * [Arguments.isPersistable]. False for a workspace whose arguments only mean something
         * inside the process that created them - a viewer streaming a `content://` URI another app
         * granted us, for example, whose grant does not outlive the task.
         *
         * A workspace that says false is still an ordinary tab in every other respect; it is only
         * skipped by the session save, so it does not come back as a dead tab on the next start.
         *
         * NOTE for implementors: several workspaces build [Info] by hand instead of through
         * `initialInfo()`. A hand-built block that forgets this field reverts to true on its first
         * emission while the seed still reads false, which persists silently and only shows up as a
         * broken tab after a restart. Carry it explicitly.
         */
        val isPersistable: Boolean = true,
    ) {
        /** What the tab chrome renders: the custom name when set, otherwise the automatic [title]. */
        val displayTitle: CaString get() = customTitle?.toCaString() ?: title

        /**
         * True if this workspace is a sub-workspace created by another workspace
         * (e.g., picker workspaces, detail views). Sub-workspaces are typically rendered as modals.
         *
         * This is a derived property used by the UI layer to determine rendering.
         * The actual presentation depends on [modalPresentation]:
         * - FULL_SCREEN → Always render as Dialog overlay covering all panes
         * - PANE_LOCAL → Render as Box overlay stacked within the parent's pane
         * - `false` (not a sub-workspace) → Render as normal workspace tab
         *
         * Domain layer sets [callerWorkspaceId], UI layer derives presentation from
         * [isSubWorkspace] and [modalPresentation].
         */
        val isSubWorkspace: Boolean get() = callerWorkspaceId != null

        val isReady: Boolean get() = lifecycleState is LifecycleState.Ready
        val isError: Boolean get() = lifecycleState is LifecycleState.Error
        val isInitializing: Boolean get() = lifecycleState is LifecycleState.Initializing
        val isPaused: Boolean get() = lifecycleState is LifecycleState.Paused
    }
}

/**
 * Returns true if these arguments are for creating a sub-workspace (modal/picker).
 * Mirrors [Workspace.Info.isSubWorkspace] for consistency.
 */
val Workspace.Arguments.isForSubWorkspace: Boolean
    get() = (this as? Workspace.ArgumentsWithCaller)?.callerWorkspaceId != null

/**
 * Returns true if a workspace created from these arguments may be paused as part of its owner's
 * ownership unit ([Workspace.ArgumentsWithCaller.pausableAsChild]).
 *
 * The [Workspace.ArgumentsForResult] veto is enforced here rather than left to each implementation:
 * a picker owes its caller a result, so releasing the caller (or the picker) would drop it.
 */
val Workspace.Arguments.isPausableAsChild: Boolean
    get() = this is Workspace.ArgumentsWithCaller && this !is Workspace.ArgumentsForResult && pausableAsChild
