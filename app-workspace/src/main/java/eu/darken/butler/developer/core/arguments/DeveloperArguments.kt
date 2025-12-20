package eu.darken.butler.developer.core.arguments

import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating a Developer workspace.
 * Sealed interface enables compile-time exhaustiveness checking.
 */
@Serializable
sealed interface DeveloperArguments : Workspace.Arguments {
    override val type: Workspace.Type get() = Workspace.Type.DEVELOPER

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        val placeholder: String = "",
    ) : DeveloperArguments
}
