package eu.darken.butler.saver.core.arguments

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
        val callerPackage: Pkg.Id?,
        /** Selected destination path, null if not yet selected */
        val destinationPath: APath<*>? = null,
    ) : SaverArguments
}
