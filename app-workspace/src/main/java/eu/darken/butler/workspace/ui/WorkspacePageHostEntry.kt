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

    /**
     * The page itself, plus every host-level side effect: activity-result launchers, event
     * collectors, error/navigation handlers, polling and resume effects. These must stay
     * single-instanced here — [Overlays] shares the same ViewModel, so registering them twice
     * would double-handle every event.
     */
    @Composable
    fun Content(id: Workspace.Id, design: WorkspaceDesign)

    /**
     * Dialogs and sheets of this page, composed by the pane layer host as a sibling of [Content]
     * so they can sit above the workspace content, above the manager dialog's peers and outside
     * the subtree that gets hidden from accessibility while covered.
     *
     * Obtain the shared ViewModel and collect the state to render — nothing else. Pages without
     * overlays implement this with an empty body; there is deliberately no default implementation,
     * because a default would let a page silently keep its dialogs in [Content] and be non-modal
     * without anything complaining.
     */
    @Composable
    fun Overlays(id: Workspace.Id, design: WorkspaceDesign)
}

val LocalWorkspacePageHosts = staticCompositionLocalOf<Map<Workspace.Type, WorkspacePageHostEntry>> {
    // Fail loudly: any composition that renders a workspace page host must provide this map.
    // A silent emptyMap() default would surface as "no page host registered" error content in
    // every detached composition (e.g. offscreen preview capture) that forgot to provide it.
    error("LocalWorkspacePageHosts not provided")
}
