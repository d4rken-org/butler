package eu.darken.butler.workspace.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

/**
 * Renders the page host UI for one [Workspace.Type].
 *
 * Implementations are contributed via `@Provides @IntoMap @WorkspaceTypeKey(...)` from each
 * workspace module and must be stateless delegates — ViewModels are obtained inside the
 * host composable via `hiltViewModel()`, never injected into the entry.
 */
interface WorkspacePageHostEntry {
    @Composable
    fun Content(id: Workspace.Id, design: WorkspaceDesign)
}

val LocalWorkspacePageHosts = staticCompositionLocalOf<Map<Workspace.Type, WorkspacePageHostEntry>> {
    // Fail loudly: any composition that renders a workspace page host must provide this map.
    // A silent emptyMap() default would surface as "no page host registered" error content in
    // every detached composition (e.g. offscreen preview capture) that forgot to provide it.
    error("LocalWorkspacePageHosts not provided")
}
