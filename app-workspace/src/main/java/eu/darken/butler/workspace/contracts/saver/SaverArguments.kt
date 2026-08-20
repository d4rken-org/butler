package eu.darken.butler.workspace.contracts.saver

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Arguments for creating a Saver workspace.
 * Used when receiving shared files via ACTION_SEND or ACTION_SEND_MULTIPLE intents.
 */
@Serializable
sealed interface SaverArguments : Workspace.Arguments {
    @IgnoredOnParcel
    override val type: Workspace.Type get() = Workspace.Type.SAVER

    @Serializable
    @SerialName("default")
    @Parcelize
    data class Default(
        /** Source content URIs as strings (Uri.toString() for serialization) */
        val sourceUris: List<String>,
        /** Package name of the app that shared the files */
        val callerPackage: Pkg.Id? = null,
        /** Selected destination path, null if not yet selected */
        val destinationPath: APath<*>? = null,
        /**
         * Set when this Saver was launched by another workspace (e.g. APK export from Apps/App
         * details) so it renders as a modal sub-workspace and is exempt from the tab limit. On
         * multi-pane layouts it then stays inside the launching workspace's pane.
         * Null for the ACTION_SEND share entry point, which stays a normal tab.
         * Session-transient: sub-workspaces are excluded from session save.
         */
        @Transient override val callerWorkspaceId: Workspace.Id? = null,
        /**
         * How this Saver is presented when it has a caller. Transient for the same reason the
         * caller is: what gets persisted is always a normal tab, for which presentation is
         * meaningless.
         */
        @Transient override val modalPresentation: Workspace.ModalPresentationMode =
            Workspace.ModalPresentationMode.PANE_LOCAL,
        /**
         * Emit [eu.darken.butler.workspace.core.WorkspaceEvent.SaveResult] when a save succeeds, so
         * the caller can act on what was written.
         *
         * Opt-in rather than implied by [callerWorkspaceId]: that one means "owned by", not
         * "waiting for a result", and the APK exports from Apps/App details launch caller-owned
         * Savers that have nobody listening.
         */
        val reportSavedPaths: Boolean = false,
    ) : SaverArguments, Workspace.ArgumentsWithCaller
}
