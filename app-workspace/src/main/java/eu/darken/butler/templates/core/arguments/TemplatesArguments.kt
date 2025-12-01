package eu.darken.butler.templates.core.arguments

import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating a Templates workspace.
 * Sealed interface enables compile-time exhaustiveness checking.
 */
@Serializable
sealed interface TemplatesArguments : Workspace.Arguments {
    override val type: Workspace.Type get() = Workspace.Type.TEMPLATES

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        val placeholder: String = "",
    ) : TemplatesArguments
}
