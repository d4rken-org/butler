package eu.darken.butler.viewer.core

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/**
 * Tab identity of a Viewer workspace derived from its arguments alone, so a dormant stand-in names
 * itself exactly like the live tab does.
 *
 * Streamed content gets no subtitle: the subtitle is the containing folder, and content another app
 * handed over does not live anywhere Butler can name.
 */
fun deriveViewerDisplay(arguments: ViewerArguments) = when (arguments) {
    is ViewerArguments.Default -> WorkspaceDisplay(
        title = arguments.filePath.name.toCaString(),
        subtitle = arguments.filePath.parent?.path?.toCaString(),
    )

    is ViewerArguments.Streamed -> WorkspaceDisplay(
        title = arguments.displayName.toCaString(),
        subtitle = null,
    )
}
