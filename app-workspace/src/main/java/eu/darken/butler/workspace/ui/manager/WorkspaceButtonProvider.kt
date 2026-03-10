package eu.darken.butler.workspace.ui.manager

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow

interface WorkspaceButtonProvider : WorkspaceActionHandler {
    val state: Flow<WorkspaceButtonViewModel.State?>
}

val LocalWorkspaceButtonProvider = staticCompositionLocalOf<WorkspaceButtonProvider?> { null }
