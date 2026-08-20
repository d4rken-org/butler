package eu.darken.butler.workspace.contracts.explorer

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating an Explorer workspace.
 * Sealed interface with multiple variants enables compile-time exhaustiveness checking.
 */
@Serializable
sealed interface ExplorerArguments : Workspace.Arguments {
    val startPath: APath<*>?

    @IgnoredOnParcel
    override val type: Workspace.Type get() = Workspace.Type.EXPLORER

    /**
     * Standard Explorer workspace for file browsing.
     * Used for normal navigation and session restoration.
     */
    @Serializable
    @SerialName("standard")
    @Parcelize
    data class Default(
        override val startPath: APath<*>? = null,
        /**
         * Non-directory location the tab was on when it was persisted (Home/Device/Trash), so a
         * restored tab is identifiable without a path. Null for directory tabs (see [startPath])
         * and for arguments saved before this field existed.
         */
        val startTarget: ExplorerStartTarget? = null,
        /**
         * Item to scroll to and highlight once the tab has arrived at [startPath], so "show me this
         * file" lands on the file instead of on a folder the user then has to search.
         *
         * Creation-only: the workspace strips it from every set of arguments it hands back, or a
         * session restore would replay the highlight forever.
         */
        val revealPath: APath<*>? = null,
    ) : ExplorerArguments

    /**
     * Explorer in picker mode for selecting files/folders.
     * Returns results to the calling workspace via WorkspaceEvent.PickerResult.
     */
    @Serializable
    @SerialName("picker")
    @Parcelize
    data class Picker(
        override val startPath: APath<*>? = null,
        @Contextual val selection: PickerConfig.Selection = PickerConfig.Selection.DirectorySingle,
        val requireWritable: Boolean = false,
        @Contextual override val callerWorkspaceId: Workspace.Id,
        /** Pane-scoped by default; a caller needing the whole screen asks for it explicitly. */
        override val modalPresentation: Workspace.ModalPresentationMode =
            Workspace.ModalPresentationMode.PANE_LOCAL,
    ) : ExplorerArguments, Workspace.ArgumentsForResult
}
