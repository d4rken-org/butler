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
    ) : ExplorerArguments, Workspace.ArgumentsForResult
}
