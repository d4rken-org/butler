package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.states.WorkspaceErrorContent

private val TAG = logTag("Workspace", "PageHostDispatcher")

/**
 * Dispatches to a type's [eu.darken.butler.workspace.ui.WorkspacePageHostEntry.Content] only.
 *
 * Overlays are deliberately not dispatched here: they are composed by the pane layer host, which
 * sits above this subtree. Routing them through here would nest them inside the workspace content
 * container again — below the manager dialog and inside the subtree that gets hidden from
 * accessibility while covered.
 */
@Composable
fun WorkspacePageHostDispatcher(
    id: Workspace.Id,
    type: Workspace.Type,
    design: WorkspaceDesign,
) {
    val entry = LocalWorkspacePageHosts.current[type]
    if (entry != null) {
        entry.Content(id = id, design = design)
    } else {
        val error = remember(type) {
            log(TAG, ERROR) { "No WorkspacePageHostEntry registered for $type" }
            IllegalStateException("No page host registered for workspace type: $type")
        }
        WorkspaceErrorContent(
            design = design,
            error = error,
            onShareError = {},
            currentWorkspaceId = id,
        )
    }
}
