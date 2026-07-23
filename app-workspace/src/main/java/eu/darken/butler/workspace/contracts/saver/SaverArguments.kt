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
         * details) so it renders as a modal sub-workspace and is exempt from the tab limit.
         * Null for the ACTION_SEND share entry point, which stays a normal tab.
         * Session-transient: sub-workspaces are excluded from session save.
         */
        @Transient override val callerWorkspaceId: Workspace.Id? = null,
    ) : SaverArguments, Workspace.ArgumentsWithCaller {
        override val modalPresentation: Workspace.ModalPresentationMode
            get() = Workspace.ModalPresentationMode.FULL_SCREEN
    }
}
