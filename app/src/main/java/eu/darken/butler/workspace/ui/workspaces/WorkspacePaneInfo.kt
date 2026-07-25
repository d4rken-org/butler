package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace

data class WorkspacePaneInfo(
    val id: Workspace.Id,
    val type: Workspace.Type,
    val lifecycleState: Workspace.LifecycleState,
    /** Carried so state overlays (e.g. the paused placeholder) can name the tab they stand for. */
    val title: CaString,
    val subtitle: CaString? = null,
)

fun Workspace.Info.asPaneInfo() = WorkspacePaneInfo(
    id = id,
    type = type,
    lifecycleState = lifecycleState,
    // displayTitle, not title: this is rendered to the user, so a custom name has to win here just
    // as it does in the rail and the tab manager, or the paused placeholder contradicts them.
    title = displayTitle,
    subtitle = subtitle,
)
