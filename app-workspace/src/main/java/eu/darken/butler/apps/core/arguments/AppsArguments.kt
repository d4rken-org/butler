package eu.darken.butler.apps.core.arguments

import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating an Apps workspace.
 * Sealed interface enables compile-time exhaustiveness checking.
 */
@Serializable
sealed interface AppsArguments : Workspace.Arguments {
    override val type: Workspace.Type get() = Workspace.Type.APPS

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        val placeholder: String? = null,
    ) : AppsArguments
}
