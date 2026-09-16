package eu.darken.butler.workspace.ui.manager

import androidx.compose.runtime.staticCompositionLocalOf
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import kotlinx.coroutines.flow.Flow

interface WorkspaceButtonProvider : WorkspaceActionHandler {
    val state: Flow<WorkspaceButtonViewModel.State?>

    /** Creates a workspace of the given quick-create type and switches to it. */
    fun createWorkspace(item: QuickCreateItem)

    /** Opens the Templates picker as a new workspace and switches to it. */
    fun createTemplatesWorkspace()

    /**
     * Persists [mode] as the layout for the given orientation; a fake or absent provider no-ops.
     *
     * @param recommendedPaneCount what the requesting window is recommended, so the entitlement
     * check can be repeated here: a geometry above it is Pro-only.
     */
    fun setPanelMode(landscape: Boolean, mode: WorkspacePanelMode, recommendedPaneCount: Int)
}

val LocalWorkspaceButtonProvider = staticCompositionLocalOf<WorkspaceButtonProvider?> { null }
