package eu.darken.butler.explorer.core.arguments

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * Arguments for opening a path in the Explorer workspace.
 *
 * This is the shared arguments class for cross-workspace navigation to Explorer.
 * Use this when you need to open Explorer at a specific path from another workspace.
 *
 * For picker mode (selecting files/folders), use [eu.darken.butler.explorer.core.picker.ExplorerPickerArguments] instead.
 */
@Parcelize
data class ExternalExplorerArguments(
    /**
     * Starting path to navigate to
     * If null, Explorer will open at the default home location
     */
    val startPath: APath<*>? = null,
) : Workspace.Arguments {
    @IgnoredOnParcel
    override val type: Workspace.Type = Workspace.Type.EXPLORER
}