package eu.darken.butler.debug.core.arguments

import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating a Debug workspace.
 * Sealed interface enables compile-time exhaustiveness checking.
 */
@Serializable
sealed interface DebugArguments : Workspace.Arguments {
    override val type: Workspace.Type get() = Workspace.Type.DEBUG

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        val placeholder: String = "",
    ) : DebugArguments
}
