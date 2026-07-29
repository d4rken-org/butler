package eu.darken.butler.workspace.contracts.viewer

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Arguments for creating a Viewer workspace. The viewer is always bound to exactly one file, so
 * unlike the Editor there is no "empty tab" variant.
 */
@Serializable
sealed interface ViewerArguments : Workspace.Arguments {
    val filePath: APath<*>

    override val type: Workspace.Type get() = Workspace.Type.VIEWER

    /**
     * @param callerWorkspaceId If set, the viewer is a drill-down of the workspace that opened the
     * file and renders as an overlay in its pane instead of as a tab. Session-transient: caller
     * relationships are not persisted because sub-workspaces are excluded from session save.
     */
    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        override val filePath: APath<*>,
        @Transient override val callerWorkspaceId: Workspace.Id? = null,
    ) : ViewerArguments, Workspace.ArgumentsWithCaller, Workspace.ArgumentsWithContentPath {
        // Get-only (no backing field): invisible to kotlinx-serialization and Parcelize, so
        // persisted session arguments are unaffected.
        // Unconditional even for a drill-down: WorkspaceRepo skips dedup for sub-workspace creates
        // and only matches non-sub holders, so an overlay can neither trigger nor satisfy a match.
        override val contentPath: APath<*>? get() = filePath

        /**
         * These arguments describe the whole workspace - one file path - so a viewer overlay can be
         * released together with the tab that opened it and rebuilt exactly as it was. It owes its
         * caller no result, so nobody is waiting on it either.
         */
        override val pausableAsChild: Boolean get() = true
    }
}
