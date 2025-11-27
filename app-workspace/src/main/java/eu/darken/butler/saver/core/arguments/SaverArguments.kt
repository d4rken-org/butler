package eu.darken.butler.saver.core.arguments

import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating a Saver workspace.
 * Used when receiving shared files via ACTION_SEND intent.
 */
@Serializable
sealed interface SaverArguments : Workspace.Arguments {
    @IgnoredOnParcel
    override val type: Workspace.Type get() = Workspace.Type.SAVER

    @Serializable
    @SerialName("default")
    @Parcelize
    data class Default(
        /** Source content URI as string (Uri.toString() for serialization) */
        val sourceUri: String,
        /** MIME type of the shared content */
        val mimeType: String?,
        /** Package name of the app that shared the file */
        val callerPackage: Pkg.Id?,
        /** Selected destination path (serialized APath), null if not yet selected */
        val destinationPath: String? = null,
        /** User-edited filename, null to extract from ContentUriHelper */
        val customFilename: String? = null,
    ) : SaverArguments
}
