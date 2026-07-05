package eu.darken.butler.workspace.contracts.editor

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating an Editor workspace.
 * Sealed interface enables compile-time exhaustiveness checking.
 */
@Serializable
sealed interface EditorArguments : Workspace.Arguments {
    override val type: Workspace.Type get() = Workspace.Type.EDITOR

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        val filePath: APath<*>? = null,
        val cursorLine: Long? = null,
        val cursorColumn: Int? = null,
        val scrollToLine: Long? = null,
        val initialContent: String? = null,
        val suggestedTitle: String? = null,
    ) : EditorArguments
}
