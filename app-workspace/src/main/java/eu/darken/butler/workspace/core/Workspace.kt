package eu.darken.butler.workspace.core

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.parcel.UuidParceler
import eu.darken.butler.workspace.core.preview.PreviewData
import kotlinx.coroutines.flow.Flow
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlin.uuid.Uuid

interface Workspace {
    val id: Id
    val type: Type
    val info: Flow<Info>

    suspend fun release() {

    }

    enum class Type {
        TEMPLATES,
        EXPLORER,
        SEARCHER,
        EDITOR,
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
     * Arguments for workspaces that are created to produce a result for another workspace
     * (e.g., picker workspaces, selection dialogs).
     *
     * Workspaces implementing this interface establish a parent-child relationship:
     * - The child workspace (sub-workspace) is typically rendered as a modal overlay
     * - When the parent closes, all children automatically close
     * - Results are returned via [eu.darken.butler.workspace.core.WorkspaceEvent]
     *
     * Example: SearcherWorkspace creates an ExplorerWorkspace with [callerWorkspaceId] = searcher's ID.
     * The Explorer picker renders as modal, returns selected path, then closes.
     *
     * @see callerWorkspaceId
     */
    interface ArgumentsForResult : Arguments {
        /**
         * ID of the workspace that created this workspace and expects a result.
         * Null if this workspace was not created to return a result.
         *
         * This property enables:
         * - Automatic lifecycle management (parent-child cleanup)
         * - UI layer derivation of presentation mode (modal vs tab)
         * - Result routing back to the caller
         */
        val callerWorkspaceId: Id?
    }

    data class Info(
        val id: Id,
        val type: Type,
        val title: CaString,
        val subtitle: CaString? = null,
        val operationCount: Int = 0,
        val attentionCount: Int = 0,
        val previewData: PreviewData? = null,
        /**
         * ID of the workspace that created this workspace, if this is a sub-workspace.
         * Null for normal workspaces.
         *
         * This is a domain property representing workspace ownership/relationship.
         * The UI layer uses this to derive presentation (via [isSubWorkspace]).
         *
         * @see ArgumentsForResult
         * @see isSubWorkspace
         */
        val callerWorkspaceId: Id? = null,
    ) {
        /**
         * True if this workspace is a sub-workspace created by another workspace
         * (e.g., picker workspaces). Sub-workspaces are typically rendered as modals.
         *
         * This is a derived property used by the UI layer to determine rendering:
         * - `true` → Render as full-screen modal dialog
         * - `false` → Render as normal workspace tab
         *
         * Domain layer sets [callerWorkspaceId], UI layer derives presentation from [isSubWorkspace].
         */
        val isSubWorkspace: Boolean get() = callerWorkspaceId != null
    }
}

