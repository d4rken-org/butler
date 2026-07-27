package eu.darken.butler.workspace.contracts.viewer

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating a Viewer workspace. The viewer is always bound to exactly one file, so
 * unlike the Editor there is no "empty tab" variant.
 */
@Serializable
sealed interface ViewerArguments : Workspace.Arguments {
    val filePath: APath<*>

    override val type: Workspace.Type get() = Workspace.Type.VIEWER

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        override val filePath: APath<*>,
    ) : ViewerArguments, Workspace.ArgumentsWithContentPath {
        // Get-only (no backing field): invisible to kotlinx-serialization and Parcelize, so
        // persisted session arguments are unaffected
        override val contentPath: APath<*>? get() = filePath
    }
}
