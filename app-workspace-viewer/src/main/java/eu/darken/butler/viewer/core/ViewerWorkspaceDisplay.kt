package eu.darken.butler.viewer.core

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/**
 * Tab identity of a Viewer workspace derived from its arguments alone, so a dormant stand-in names
 * itself exactly like the live tab does.
 */
fun deriveViewerDisplay(arguments: ViewerArguments) = WorkspaceDisplay(
    title = arguments.filePath.name.toCaString(),
    subtitle = arguments.filePath.parent?.path?.toCaString(),
)
