package eu.darken.butler.workspace.core

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.parcel.UuidParceler
import kotlinx.coroutines.flow.Flow
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlin.uuid.Uuid

interface Workspace<ArgT : Workspace.Arguments> {
    val id: Id
    val type: Type
    val info: Flow<Info>

    /**
     * The arguments used to create this workspace.
     * For session restoration, workspaces may return current state as Arguments
     * instead of original creation arguments.
     */
    suspend fun createArguments(): ArgT

    suspend fun release() {

    }

    enum class Type {
        TEMPLATES,
        EXPLORER,
        SEARCHER,
        EDITOR,
        APPS,
        APP_DETAILS,
        ;
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
         * Rendering:
         * - Phone (single-pane): Dialog overlay covering entire screen
         * - Tablet (multi-pane): Dialog overlay covering all panes
         */
        FULL_SCREEN,

        /**
         * Render as overlay local to parent's pane.
         * Used for detail/informational workspaces opened from a specific parent workspace.
         *
         * Rendering:
         * - Phone (single-pane): Dialog overlay covering entire screen
         * - Tablet (multi-pane): Box overlay covering only the parent's pane
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
     * The app details renders as overlay within the Apps pane on tablets, or full-screen on phones.
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
    }

    /**
     * Arguments for workspaces that are created to produce a result for another workspace
     * (e.g., picker workspaces, selection dialogs).
     *
     * This interface extends [ArgumentsWithCaller] for workspaces that:
     * - Return results to their parent via [eu.darken.butler.workspace.core.WorkspaceEvent]
     * - Require focused user interaction (defaults to FULL_SCREEN presentation)
     *
     * Example: SearcherWorkspace creates ExplorerPickerWorkspace to select a directory.
     * The Explorer picker renders as full-screen modal, returns selected path, then closes.
     *
     * @see ArgumentsWithCaller
     */
    interface ArgumentsForResult : ArgumentsWithCaller {
        /**
         * Pickers default to full-screen presentation for focused interaction.
         * Caller can override this if context allows pane-aware rendering.
         */
        override val modalPresentation: ModalPresentationMode
            get() = ModalPresentationMode.FULL_SCREEN
    }

    data class Info(
        val id: Id,
        val type: Type,
        val title: CaString,
        val subtitle: CaString? = null,
        val operationCount: Int = 0,
        val attentionCount: Int = 0,
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
         * - PANE_LOCAL: Box overlay within parent's pane on tablets, Dialog on phones
         *
         * @see ModalPresentationMode
         */
        val modalPresentation: ModalPresentationMode = ModalPresentationMode.PANE_LOCAL,
    ) {
        /**
         * True if this workspace is a sub-workspace created by another workspace
         * (e.g., picker workspaces, detail views). Sub-workspaces are typically rendered as modals.
         *
         * This is a derived property used by the UI layer to determine rendering.
         * The actual presentation depends on [modalPresentation]:
         * - FULL_SCREEN → Always render as Dialog overlay covering all panes
         * - PANE_LOCAL → Render as Box overlay within parent's pane on tablets, Dialog on phones
         * - `false` (not a sub-workspace) → Render as normal workspace tab
         *
         * Domain layer sets [callerWorkspaceId], UI layer derives presentation from
         * [isSubWorkspace] and [modalPresentation].
         */
        val isSubWorkspace: Boolean get() = callerWorkspaceId != null
    }
}

