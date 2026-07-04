package eu.darken.butler.workspace.ui.manager

import androidx.compose.runtime.staticCompositionLocalOf
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import kotlinx.coroutines.flow.Flow

interface WorkspaceButtonProvider : WorkspaceActionHandler {
    val state: Flow<WorkspaceButtonViewModel.State?>

    /** Creates a workspace of the given quick-create type and switches to it. */
    fun createWorkspace(item: QuickCreateItem)

    /** Opens the Templates picker as a new workspace and switches to it. */
    fun createTemplatesWorkspace()
}

val LocalWorkspaceButtonProvider = staticCompositionLocalOf<WorkspaceButtonProvider?> { null }
