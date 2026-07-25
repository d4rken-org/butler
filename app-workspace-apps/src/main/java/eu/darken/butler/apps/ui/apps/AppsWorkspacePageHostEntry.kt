package eu.darken.butler.apps.ui.apps

import androidx.compose.runtime.Composable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

object AppsWorkspacePageHostEntry : WorkspacePageHostEntry {

    @Composable
    override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
        AppsWorkspacePageHost(id = id, design = design)
    }

    @Composable
    override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        // No pane-scoped overlays
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object PageHostModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.APPS)
        fun entry(): WorkspacePageHostEntry = AppsWorkspacePageHostEntry
    }
}
